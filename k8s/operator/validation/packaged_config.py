#!/usr/bin/env python3
"""Package a secret-free source copy, then check real agent startup without running a review."""
import io
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]


def run(command, *, cwd, env=None, timeout=120):
    result = subprocess.run(command, cwd=cwd, env=env, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed: {command[0]}\n{result.stdout}\n{result.stderr}")
    return result


def audit(archive):
    for name in archive.namelist():
        leaf = Path(name).name
        if leaf.startswith("application-local.") or leaf == ".kubeconfig":
            raise RuntimeError(f"Forbidden local configuration in packaged artifact: {name}")
        if name.endswith(".jar"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                audit(nested)


def require_nested_module_jars(archive):
    """Local modules must be nested jars. Directory entries break Boot's LaunchedClassLoader (KTC-5686)."""
    classpath = archive.read("BOOT-INF/classpath.idx").decode()
    required = ("BOOT-INF/lib/events-jvm.jar", "BOOT-INF/lib/shared-jvm.jar")
    names = set(archive.namelist())
    for entry in required:
        quoted = f'"{entry}"'
        if quoted not in classpath:
            raise RuntimeError(f"Executable classpath missing nested module jar {entry}:\n{classpath}")
        if entry not in names:
            raise RuntimeError(f"Executable archive missing nested module jar file {entry}")
    for line in classpath.splitlines():
        if "kotlin-output" in line or "resources-output" in line:
            raise RuntimeError(
                "Executable classpath still lists unpacked module directories; "
                "set settings.jvm.runtimeClasspathMode=jars for code-review packaging"
            )


def embed_probe(artifact: Path, probe_classes: Path, destination: Path) -> Path:
    """
    Boot 4 no longer ships DelegatingApplicationContextInitializer, so
    context.initializer.classes / PropertiesLauncher loader.path cannot register a probe.
    Embed the test-only initializer into a temporary executable copy via spring.factories.
    """
    shutil.copy2(artifact, destination)
    with zipfile.ZipFile(destination, "a") as archive:
        for path in probe_classes.rglob("*"):
            if path.is_file():
                archive.write(path, arcname=f"BOOT-INF/classes/{path.relative_to(probe_classes).as_posix()}")
        archive.writestr(
            "BOOT-INF/classes/META-INF/spring.factories",
            "org.springframework.context.ApplicationContextInitializer="
            "org.sift.operator.validation.ConfigProbe\n",
        )
    return destination


def main():
    output = ROOT / "build" / "operator-config-validation"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="startup-", dir=output) as temporary:
        work = Path(temporary)
        stage = work / "source"
        stage.mkdir()
        for name in ("agents", "events", "server", "k8s", "config", "build-config"):
            shutil.copytree(ROOT / name, stage / name, ignore=shutil.ignore_patterns(
                "application-local.*", ".kubeconfig", "build", "validation", ".DS_Store",
            ))
        for name in ("project.yaml", "libs.versions.toml", "kotlin", "kotlin.bat"):
            shutil.copy2(ROOT / name, stage / name)
        run([str(ROOT / "kotlin"), "package", "--project-dir", str(stage), "--module", "code-review",
             "--format", "executable-jar"], cwd=stage)
        artifact = stage / "build/tasks/_code-review_executableJarJvm/code-review-jvm-executable.jar"
        libraries = work / "libraries"
        libraries.mkdir()
        with zipfile.ZipFile(artifact) as archive:
            audit(archive)
            require_nested_module_jars(archive)
            for name in archive.namelist():
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    (libraries / Path(name).name).write_bytes(archive.read(name))
        classes = work / "classes"
        classes.mkdir()
        run(["javac", "-cp", str(libraries / "*"), "-d", str(classes),
             str(ROOT / "k8s/operator/validation/ConfigProbe.java")], cwd=work)
        mounted = work / "review.yaml"
        mounted.write_text('''sift.review.repository-url: https://example.org/mounted.git
sift.review.branch: mounted-branch
sift.review.base-branch: mounted-base
sift.review.pull-request: '73'
sift.review.commit-sha: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
sift.review.execution-id: probe-uid:7
spring.rabbitmq.host: mounted-rabbit
spring.ai.openai.base-url: http://model/wire/${SIFT_MODEL_PROXY_TOKEN}/codex/openai/v1
spring.ai.openai.chat.options.model: mounted-model
''')
        env = {key: value for key, value in os.environ.items() if not key.startswith((
            "SPRING_", "SIFT_", "OPENAI_", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "TOKEN",
        ))}
        env.update(OPENAI_API_KEY="probe-key", SIFT_MODEL_PROXY_TOKEN="probe-token",
                   SPRING_CONFIG_ADDITIONAL_LOCATION=mounted.as_uri())
        missing_env = dict(env, SPRING_CONFIG_ADDITIONAL_LOCATION=(work / "missing.yaml").as_uri())
        missing = subprocess.run(["java", "-jar", str(artifact)], cwd=work, env=missing_env,
                                 text=True, capture_output=True, timeout=30)
        if missing.returncode == 0 or "does not exist" not in missing.stdout + missing.stderr:
            raise RuntimeError("Missing mandatory configuration did not fail packaged JarLauncher startup")
        print("PASS: missing mandatory additional configuration fails packaged JarLauncher startup", flush=True)
        print("PASS: recursive artifact audit excludes local configuration before packaging", flush=True)
        print("PASS: executable packages events/shared as nested jars (runtimeClasspathMode=jars)", flush=True)

        probed = embed_probe(artifact, classes, work / "probed-agent.jar")
        result = run(["java", "-jar", str(probed)], cwd=work, env=env, timeout=30)
        if "PACKAGED_CONFIG_PROBE_OK" not in result.stdout:
            raise RuntimeError(f"Startup probe did not execute\n{result.stdout}\n{result.stderr}")
        print(
            "PASS: packaged agent binding, mounted precedence, packaged defaults, "
            "advisor presence, missing-file failure via SPRING_CONFIG_ADDITIONAL_LOCATION",
            flush=True,
        )
        print(
            "BLOCKED external contract: commit-sha/execution-id checked as environment properties, "
            "not bound agent fields",
            flush=True,
        )


if __name__ == "__main__":
    main()
