#!/usr/bin/env python3
"""Non-destructive kind development commands. Python standard library + kubectl only."""
import argparse
import base64
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
NAMESPACE = "sift-dev"
MANAGER = {"app.kubernetes.io/managed-by": "sift-local-dev"}
SECRET = "sift-local-credentials"
PORTS = {"model": (19516, 29516), "search": (8888, 28888), "messaging": (5672, 25672)}


class Cluster:
    def __init__(self, context):
        if not (ROOT / ".kubeconfig").is_file() or not context.startswith("kind-"):
            raise RuntimeError("Require root .kubeconfig and an explicit kind-* context")
        self.context = context
        self.base = ["kubectl", "--kubeconfig", str(ROOT / ".kubeconfig"), "--context", context,
                     "--request-timeout=10s"]
        current = self.call("config", "current-context").stdout.strip()
        if current != context:
            raise RuntimeError("Expected context differs from root kubeconfig current-context; no changes made")
        nodes = self.get("nodes")["items"]
        local = subprocess.run(["kind", "get", "nodes", "--name", context.removeprefix("kind-")],
                               text=True, capture_output=True, timeout=15)
        if local.returncode or not nodes or {n["metadata"]["name"] for n in nodes} != set(local.stdout.split()):
            raise RuntimeError("API nodes do not match the explicitly selected local kind cluster")
        print("Context:", context, "namespace:", NAMESPACE, "architectures:",
              ",".join(sorted({n["status"]["nodeInfo"]["architecture"] for n in nodes})))

    def call(self, *args, value=None, check=True, timeout=25):
        result = subprocess.run(self.base + list(args), input=json.dumps(value) if value is not None else None,
                                text=True, capture_output=True, timeout=timeout)
        if check and result.returncode:
            # API errors can echo submitted objects, URLs and credentials. Never print them here.
            raise RuntimeError("kubectl operation failed (details suppressed to protect credentials)")
        return result

    def get(self, *args):
        output = self.call("get", *args, "-o", "json", "--ignore-not-found").stdout
        if output.strip():
            return json.loads(output)
        return {"items": []} if len(args) == 1 or args[1].startswith("-") else None

    def namespace(self):
        namespace = self.get("namespace", NAMESPACE)
        if not namespace or namespace.get("status", {}).get("phase") != "Active":
            raise RuntimeError("sift-dev must exist and be Active; run apply first")

    def render(self, path):
        output = self.call("create", "--dry-run=client", "-f", str(path), "-o", "json").stdout.strip()
        values = []
        while output:
            value, end = json.JSONDecoder().raw_decode(output)
            values.extend(value["items"] if value.get("kind") == "List" else [value])
            output = output[end:].lstrip()
        return values

    def owned(self, value):
        old = self.get(value["kind"], value["metadata"]["name"], "-n", NAMESPACE)
        if old and any(old["metadata"].get("labels", {}).get(k) != v for k, v in MANAGER.items()):
            raise RuntimeError("Refusing to overwrite an existing resource not managed by sift-local-dev")
        return old


def apply(cluster, dry_run=False, relays=(), relay_address=None):
    if relays and (relay_address is None or relay_address.is_loopback or relay_address.is_unspecified
                   or relay_address.is_multicast):
        raise RuntimeError("Relay mode requires the specific non-loopback IPv4 address passed to host_relay --bind")
    namespace = cluster.get("namespace", NAMESPACE)
    if namespace is None:
        value = cluster.render(ROOT / "k8s/manifests/local/namespace.yaml")[0]
        cluster.call("create", "-f", "-", *(["--dry-run=server"] if dry_run else []), value=value)
        if dry_run:
            raise RuntimeError("Namespace admission passed; create namespace with apply before namespaced server dry-run")
    cluster.namespace()
    crd = cluster.get("crd", "codereviews.sift.org")
    if not crd or not any(c["type"] == "Established" and c["status"] == "True"
                          for c in crd.get("status", {}).get("conditions", [])):
        raise RuntimeError("Administrator must install the generated CodeReview CRD first")
    paths = ["operator/service-account.yaml", "operator/role.yaml", "operator/role-binding.yaml",
             "local/review-service-account.yaml", "local/bridges.yaml"]
    resources = [v for path in paths for v in cluster.render(ROOT / "k8s/manifests" / path)]
    config = next(v for v in resources if v["kind"] == "ConfigMap")
    for service in relays:
        direct, relay = PORTS[service]
        config["data"]["haproxy.cfg"] = config["data"]["haproxy.cfg"].replace(
            f"host.docker.internal:{direct} ", f"{relay_address}:{relay} ")
    deployment = next(v for v in resources if v["kind"] == "Deployment")
    deployment["spec"]["template"]["metadata"]["annotations"] = {
        "sift.org/bridge-config": hashlib.sha256(config["data"]["haproxy.cfg"].encode()).hexdigest()}
    for value in resources:
        if value["metadata"].get("namespace") != NAMESPACE:
            raise RuntimeError("Unexpected manifest namespace")
        cluster.owned(value)
    for value in resources:
        cluster.call("apply", "-f", "-", *(["--dry-run=server"] if dry_run else []), value=value)
    print("PASS: infrastructure server dry-run" if dry_run else "Applied managed infrastructure; no cluster/CRD replacement")


def rbac(cluster):
    cluster.namespace()
    account = f"system:serviceaccount:{NAMESPACE}:sift-operator"
    checks = [(verb, resource, True, NAMESPACE) for resource, verbs in {
        "codereviews.sift.org": ["get", "list", "watch"],
        "codereviews.sift.org/status": ["update"],
        "configmaps": ["get", "list", "watch", "create", "delete"],
        "jobs.batch": ["get", "list", "watch", "create", "delete"],
        "pods": ["get", "list", "watch"],
    }.items() for verb in verbs]
    checks += [(verb, resource, False, namespace) for verb, resource, namespace in [
        ("get", "secrets", NAMESPACE), ("list", "secrets", NAMESPACE), ("create", "secrets", NAMESPACE),
        ("create", "customresourcedefinitions.apiextensions.k8s.io", NAMESPACE),
        ("update", "customresourcedefinitions.apiextensions.k8s.io", NAMESPACE),
        ("delete", "pods", NAMESPACE), ("create", "pods", NAMESPACE),
        ("create", "codereviews.sift.org", NAMESPACE), ("update", "jobs.batch", NAMESPACE),
        ("list", "jobs.batch", "default"), ("list", "nodes", NAMESPACE),
    ]]
    for verb, resource, allowed, namespace in checks:
        parts = resource.split("/", 1)
        arguments = ["auth", "can-i", verb, parts[0], "-n", namespace, "--as", account,
                     "--as-group=system:serviceaccounts", f"--as-group=system:serviceaccounts:{NAMESPACE}",
                     "--as-group=system:authenticated"]
        if len(parts) == 2:
            arguments += ["--subresource", parts[1]]
        result = cluster.call(*arguments, check=False)
        if result.stdout.strip() != ("yes" if allowed else "no"):
            raise RuntimeError(f"RBAC mismatch: {verb} {resource} in {namespace}")
    result = cluster.call("auth", "can-i", "list", "jobs.batch", "--all-namespaces", "--as", account,
                          "--as-group=system:serviceaccounts", f"--as-group=system:serviceaccounts:{NAMESPACE}",
                          "--as-group=system:authenticated", check=False)
    if result.stdout.strip() != "no":
        raise RuntimeError("Operator ServiceAccount has cluster-wide Job access")
    host = cluster.call("auth", "can-i", "get", "secrets", "-n", NAMESPACE, check=False).stdout.strip()
    print(f"PASS: {len(checks) + 1} prepared ServiceAccount permission checks; host Secret read: {host}")
    print("Host JVM uses root kubeconfig credentials, NOT this ServiceAccount")


def local_credentials():
    """Import only the existing simple local-file shape; reject ambiguous YAML rather than guess."""
    content = (ROOT / "agents/code-review/resources/application-local.yaml").read_text()

    def scalar(key, indent=0):
        matches = re.findall(r"^" + " " * indent + re.escape(key) + r": ([^\n]+)$", content, re.MULTILINE)
        if len(matches) != 1:
            raise RuntimeError("Local file shape changed; use environment or hidden credential prompts instead")
        value = matches[0]
        if value.startswith('"'):
            value = json.loads(value)
        elif not re.fullmatch(r"[A-Za-z0-9_./:+-]+", value):
            raise RuntimeError("Unsupported local YAML scalar; use environment or hidden credential prompts")
        return value

    if scalar("OPENAI_BASE_URL") != "http://127.0.0.1:19516/wire/${TOKEN}/codex/openai/v1":
        raise RuntimeError("Local model path changed; review bridge URL mapping before importing credentials")
    return {"OPENAI_API_KEY": scalar("OPENAI_API_KEY"), "SIFT_MODEL_PROXY_TOKEN": scalar("TOKEN"),
            "SPRING_RABBITMQ_PASSWORD": scalar("password", 4)}


def secrets(cluster, agent_local=False):
    cluster.namespace()
    value = {"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
             "metadata": {"name": SECRET, "namespace": NAMESPACE, "labels": MANAGER}}
    old = cluster.owned(value)
    data = dict(old.get("data", {})) if old else {}
    supplied = local_credentials() if agent_local else os.environ
    for key, env in [("model-api-key", "OPENAI_API_KEY"), ("proxy-token", "SIFT_MODEL_PROXY_TOKEN"),
                     ("rabbitmq-password", "SPRING_RABBITMQ_PASSWORD")]:
        content = supplied.get(env)
        if content is None:
            if not sys.stdin.isatty():
                raise RuntimeError("Noninteractive credential provisioning requires environment values or --from-agent-local")
            content = getpass.getpass(f"{env} (hidden): ")
        if not content or "\n" in content:
            raise RuntimeError("Credential must be nonempty and single-line")
        data[key] = base64.b64encode(content.encode()).decode()
    if os.environ.get("SIFT_REVIEW_AUTH_TOKEN"):
        data["git-token"] = base64.b64encode(os.environ["SIFT_REVIEW_AUTH_TOKEN"].encode()).decode()
    value["data"] = data
    if old:
        value["metadata"]["resourceVersion"] = old["metadata"]["resourceVersion"]
    cluster.call("replace" if old else "create", "-f", "-", value=value)
    print("Provisioned credential Secret via stdin; no values or last-applied annotation written")


def probe(cluster, model=False):
    cluster.namespace()
    name = "sift-bridge-probe-" + uuid.uuid4().hex[:8]
    container = {"name": "probe", "image": "python:3.14-alpine", "command": ["sleep", "600"],
                 "securityContext": {"allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True,
                                     "capabilities": {"drop": ["ALL"]}},
                 "resources": {"requests": {"cpu": "10m", "memory": "32Mi"},
                               "limits": {"cpu": "200m", "memory": "128Mi"}}}
    if model:
        container["env"] = [{"name": env, "valueFrom": {"secretKeyRef": {"name": SECRET, "key": key}}}
                            for env, key in [("OPENAI_API_KEY", "model-api-key"),
                                             ("SIFT_MODEL_PROXY_TOKEN", "proxy-token")]]
    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name, "namespace": NAMESPACE},
           "spec": {"restartPolicy": "Never", "activeDeadlineSeconds": 600,
                    "serviceAccountName": "sift-review", "automountServiceAccountToken": False,
                    "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "runAsGroup": 10001,
                                        "seccompProfile": {"type": "RuntimeDefault"}}, "containers": [container]}}
    cluster.call("create", "-f", "-", value=pod)
    try:
        cluster.call("wait", "-n", NAMESPACE, "--for=condition=Ready", f"pod/{name}", "--timeout=60s", timeout=70)
        script = (ROOT / "k8s/local/probe.py").read_text()
        result = subprocess.run(cluster.base + ["exec", "-i", "-n", NAMESPACE, name, "--", "python", "-",
                                                *(["--model"] if model else [])],
                                input=script, text=True, capture_output=True, timeout=120)
        print(result.stdout, end="")
        if result.returncode:
            raise RuntimeError("Bridge probe failed; upstream details intentionally suppressed")
    finally:
        cluster.call("delete", "pod", name, "-n", NAMESPACE, "--wait=false", check=False)


def run_operator(cluster):
    cluster.namespace()
    if not os.environ.get("SIFT_REVIEW_IMAGE", "").strip():
        raise RuntimeError("Set SIFT_REVIEW_IMAGE to a trusted full image tag/digest")
    if any(value for key, value in os.environ.items()
           if key.startswith(("SPRING_PROFILES_", "SPRING_CONFIG_", "KUBERNETES_")) or key == "SPRING_APPLICATION_JSON"):
        raise RuntimeError("Unset Spring config/profile and Kubernetes client environment overrides before using this helper")
    env = os.environ.copy()
    env.update(KUBECONFIG=str(ROOT / ".kubeconfig"), SIFT_OPERATOR_NAMESPACE=NAMESPACE,
               SPRING_CONFIG_ADDITIONAL_LOCATION=(ROOT / "k8s/local/operator.yaml").as_uri())
    for key in ("OPENAI_API_KEY", "SIFT_MODEL_PROXY_TOKEN", "SPRING_RABBITMQ_PASSWORD", "SIFT_REVIEW_AUTH_TOKEN"):
        env.pop(key, None)
    print("Starting operator with HOST kubeconfig identity", flush=True)
    os.chdir(ROOT)
    os.execve(str(ROOT / "kotlin"), [str(ROOT / "kotlin"), "run", "--module", "operator"], env)


def observe(cluster, name):
    cluster.namespace()
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        cr = cluster.get("codereview", name, "-n", NAMESPACE)
        if cr is None:
            raise RuntimeError("CodeReview not found")
        status = cr.get("status", {})
        if status.get("observedGeneration") == cr["metadata"]["generation"] and status.get("jobRef"):
            job = cluster.get("job", status["jobRef"]["name"], "-n", NAMESPACE)
            config = cluster.get("configmap", status["configMapRef"]["name"], "-n", NAMESPACE)
            for resource, ref in [(job, status["jobRef"]), (config, status["configMapRef"])]:
                if not resource or resource["metadata"]["uid"] != ref["uid"] or not any(
                    owner["uid"] == cr["metadata"]["uid"] and owner.get("controller")
                    for owner in resource["metadata"].get("ownerReferences", [])
                ):
                    raise RuntimeError("Current owned resource identity mismatch")
            print(json.dumps({"crUid": cr["metadata"]["uid"], "generation": cr["metadata"]["generation"],
                              "executionId": status["executionId"], "commitSha": status["commitSha"],
                              "jobUid": job["metadata"]["uid"], "phase": status["phase"]}))
            print("PASS: current-generation owned Job/ConfigMap observed; NOT an execution/event success gate")
            return
        time.sleep(0.5)
    raise RuntimeError("Timed out waiting for operator-created current-generation resources")


def apply_review(cluster, name, repository, branch, base_branch, sha, pull_request=None):
    cluster.namespace()
    url = urlsplit(repository)
    if (not re.fullmatch(r"[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?", name)
            or not re.fullmatch(r"[0-9a-fA-F]{40}", sha)
            or url.scheme not in ("http", "https") or not url.hostname or url.username or url.password
            or url.query or url.fragment
            or any(not v or re.search(r"\s|\$\{|[{}]", v) for v in [repository, branch, base_branch])):
        raise RuntimeError("Require valid name, nonsecret HTTP(S) repository, literal branches and full commit SHA")
    value = {"apiVersion": "sift.org/v1alpha1", "kind": "CodeReview",
             "metadata": {"name": name, "namespace": NAMESPACE, "labels": MANAGER},
             "spec": {"repositoryUrl": repository, "branch": branch, "baseBranch": base_branch, "commitSha": sha}}
    if pull_request is not None:
        if not re.fullmatch(r"[1-9][0-9]*", pull_request):
            raise RuntimeError("Pull request must be a positive number")
        value["spec"]["pullRequest"] = pull_request
    cluster.owned(value)
    cluster.call("apply", "-f", "-", value=value)
    print("Applied CodeReview only; the operator creates its Job and ConfigMap")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    apply_parser = commands.add_parser("apply")
    apply_parser.add_argument("--dry-run", action="store_true")
    apply_parser.add_argument("--relay-service", action="append", choices=PORTS, default=[])
    apply_parser.add_argument("--relay-address", type=ipaddress.IPv4Address)
    commands.add_parser("rbac")
    secret_parser = commands.add_parser("secrets")
    secret_parser.add_argument("--from-agent-local", action="store_true",
                               help="Explicitly import credentials from the ignored existing agent local YAML")
    probe_parser = commands.add_parser("probe")
    probe_parser.add_argument("--model", action="store_true", help="Make one small streaming model request (costs tokens)")
    commands.add_parser("run")
    observer = commands.add_parser("observe")
    observer.add_argument("name")
    review_parser = commands.add_parser("apply-review")
    for option in ("name", "repository", "branch", "base-branch", "sha"):
        review_parser.add_argument("--" + option, required=True)
    review_parser.add_argument("--pull-request")
    args = parser.parse_args()
    cluster = Cluster(args.context)
    if args.command == "apply":
        apply(cluster, args.dry_run, args.relay_service, args.relay_address)
    elif args.command == "probe":
        probe(cluster, args.model)
    elif args.command == "observe":
        observe(cluster, args.name)
    elif args.command == "apply-review":
        apply_review(cluster, args.name, args.repository, args.branch, args.base_branch, args.sha, args.pull_request)
    elif args.command == "rbac":
        rbac(cluster)
    elif args.command == "secrets":
        secrets(cluster, args.from_agent_local)
    elif args.command == "run":
        run_operator(cluster)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as error:
        print(f"FAILED: {error}", file=sys.stderr)
        sys.exit(1)
    except (subprocess.TimeoutExpired, EOFError, OSError, ValueError):
        # No traceback: credentials may appear in subprocess arguments/errors from upstreams.
        print("FAILED: local command; check prerequisites/context/permissions and command-specific progress above", file=sys.stderr)
        sys.exit(1)