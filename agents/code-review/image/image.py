#!/usr/bin/env python3
"""Build, audit and publish a review image. Candidate publication is explicitly NOT acceptance."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import zipfile

from artifact import audit

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / 'build/review-image'
REPOSITORY = 'jbfpietzko/shift-code-review-agent'
FORBIDDEN = {'curl', 'wget', 'nc', 'netcat', 'ncat', 'socat', 'telnet', 'ping', 'dig', 'nslookup',
             'tcpdump', 'nmap', 'ssh', 'scp', 'apt', 'apt-get', 'dpkg', 'gcc', 'cc', 'make', 'javac',
             'jshell', 'jwebserver', 'python', 'python3', 'pip', 'node', 'npm', 'mvn', 'gradle', 'kotlin'}


def run(command, timeout=120, check=True):
    result = subprocess.run([str(v) for v in command], cwd=ROOT, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{command[0]} failed:\n{result.stdout}\n{result.stderr}')
    return result


def export(image, work):
    container = run(['docker', 'create', image]).stdout.strip()
    try:
        run(['docker', 'cp', f'{container}:/app/agent.jar', work / 'agent.jar'])
        run(['docker', 'export', '-o', work / 'rootfs.tar', container])
    finally:
        run(['docker', 'rm', container])
    return work / 'agent.jar'


def filesystem_audit(path):
    with tarfile.open(path) as archive:
        for member in archive:
            leaf = Path(member.name).name
            if ((member.isfile() or member.issym()) and leaf in FORBIDDEN
                    or leaf.startswith('application-local.') or leaf in ('.kubeconfig', '.env', '.git-credentials')
                    or member.name.startswith(('root/.', 'source/', 'run/secrets/'))):
                raise RuntimeError(f'Forbidden image content: {member.name}')
    return True


def secured(image, command=(), mounts=(), env=(), entrypoint=None, check=True):
    args = ['docker', 'run', '--rm', '--read-only', '--user', '10001:10001', '--cap-drop=ALL',
            '--security-opt=no-new-privileges', '--memory=2g', '--cpus=2',
            '--tmpfs', '/tmp:rw,nosuid,nodev,size=512m,uid=10001,gid=10001',
            '--tmpfs', '/scratch:rw,nosuid,nodev,size=512m,uid=10001,gid=10001']
    for source, target in mounts:
        args += ['--mount', f'type=bind,src={source},dst={target},readonly']
    for value in env:
        args += ['-e', value]
    if entrypoint:
        args += ['--entrypoint', entrypoint]
    return run(args + [image] + list(command), check=check)


def config_probe(image, artifact, work):
    spec = importlib.util.spec_from_file_location('packaged_config', ROOT / 'k8s/operator/validation/packaged_config.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    libraries, classes = work / 'libraries', work / 'classes'
    libraries.mkdir()
    classes.mkdir()
    with zipfile.ZipFile(artifact) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries / Path(name).name).write_bytes(archive.read(name))
    run(['javac', '-cp', str(libraries / '*'), '-d', classes,
         ROOT / 'k8s/operator/validation/ConfigProbe.java',
         ROOT / 'agents/code-review/image/ToolProbe.java'])
    probed = module.embed_probe(artifact, classes, work / 'probed-agent.jar')
    mounted = work / 'application.yaml'
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
    environment = ['OPENAI_API_KEY=probe-key', 'SIFT_MODEL_PROXY_TOKEN=probe-token',
                   'SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/sift/review/application.yaml']
    result = secured(image, mounts=[(probed, '/app/agent.jar'), (mounted, '/etc/sift/review/application.yaml')],
                     env=environment)
    if 'PACKAGED_CONFIG_PROBE_OK' not in result.stdout:
        raise RuntimeError('Mounted configuration probe did not run')
    missing = secured(image, env=environment, check=False)
    if not missing.returncode or 'does not exist' not in missing.stdout + missing.stderr:
        raise RuntimeError('Missing mandatory mounted file did not fail startup')
    # Run the actual packaged tool dependency in the image; no model or review execution.
    result = secured(image, entrypoint='/opt/java/openjdk/bin/java',
                     mounts=[(libraries, '/probe/lib'), (classes, '/probe/classes')],
                     command=['-cp', '/probe/lib/*:/probe/classes', 'ToolProbe'])
    if 'PACKAGED_TOOLS_OK' not in result.stdout:
        raise RuntimeError('Packaged tools did not pass')
    return {'additionalLocation': True, 'mountedBindingAndPrecedence': True,
            'packagedDefaultsRetained': True, 'missingFileFails': True, 'packagedTools': True}


def validate(image):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=OUTPUT) as directory:
        work = Path(directory)
        artifact = export(image, work)
        evidence = audit(artifact)
        evidence['artifactSha256'] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        evidence['filesystemAudit'] = filesystem_audit(work / 'rootfs.tar')
        configuration = json.loads(run(['docker', 'image', 'inspect', image]).stdout)[0]
        if configuration['Config']['User'] != '10001:10001':
            raise RuntimeError('Unexpected runtime user')
        evidence['imageId'] = configuration['Id']
        evidence['architecture'] = configuration['Architecture']
        evidence['configuration'] = config_probe(image, artifact, work)
    (OUTPUT / 'audit.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(json.dumps(evidence, indent=2))
    return evidence


def publish(image, tag, candidate):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{1,100}', tag):
        raise RuntimeError('Use a unique lowercase immutable tag')
    if candidate and not tag.startswith('candidate-'):
        raise RuntimeError('Non-acceptance images must use a candidate-* tag')
    evidence = validate(image)
    if evidence['missingIdentityMembers'] and not candidate:
        raise RuntimeError('BLOCKED: identity contract absent; only explicit candidate publication allowed')
    evidence['scan'] = scan(image)
    target = f'{REPOSITORY}:{tag}'
    exists = run(['docker', 'buildx', 'imagetools', 'inspect', target], check=False)
    if exists.returncode == 0:
        raise RuntimeError('Refusing to overwrite an existing registry tag')
    if 'not found' not in exists.stderr.lower() and 'manifest unknown' not in exists.stderr.lower():
        raise RuntimeError('Cannot establish registry tag absence (authentication/network error)')
    run(['docker', 'tag', image, target])
    result = run(['docker', 'push', target], timeout=120)
    digest = re.search(r'digest: (sha256:[0-9a-f]{64})', result.stdout + result.stderr)
    if not digest:
        raise RuntimeError('Registry push did not report a digest')
    reference = f'{REPOSITORY}@{digest[1]}'
    run(['docker', 'pull', reference])
    pulled = json.loads(run(['docker', 'image', 'inspect', reference]).stdout)[0]
    if pulled['Id'] != evidence['imageId']:
        raise RuntimeError('Pulled image differs from audited image')
    evidence.update(publishedImage=reference, tag=target, candidate=candidate)
    (OUTPUT / 'publication.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('PUBLISHED:', reference, '(candidate; NOT acceptance)' if candidate else '(E2E still required)')


def scan(image):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    run(['trivy', 'image', '--scanners', 'vuln,secret', '--format', 'json',
         '--output', OUTPUT / 'trivy.json', image], timeout=180)
    result = json.loads((OUTPUT / 'trivy.json').read_text())
    if result.get('Metadata', {}).get('OS', {}).get('Family') != 'ubuntu':
        raise RuntimeError('Scanner failed to identify runtime OS; do not claim a complete scan')
    findings = [v for target in result.get('Results', []) for v in target.get('Vulnerabilities', [])]
    if any(target.get('Secrets') for target in result.get('Results', [])):
        raise RuntimeError('Secret scanner found potential credentials; publication blocked')
    summary = {'secretFindings': 0, 'vulnerabilities': [
        {key: value.get(key) for key in ('VulnerabilityID', 'PkgName', 'InstalledVersion', 'FixedVersion', 'Severity')}
        for value in findings]}
    (OUTPUT / 'scan-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print('Scan complete:', len(findings), 'unresolved vulnerabilities; no scanner-reported secrets')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('build')
    build.add_argument('--platform', required=True, choices=['linux/arm64', 'linux/amd64'])
    build.add_argument('--image', default='sift-review:candidate')
    check = commands.add_parser('audit')
    check.add_argument('--image', required=True)
    scanner = commands.add_parser('scan')
    scanner.add_argument('--image', required=True)
    push = commands.add_parser('publish')
    push.add_argument('--image', required=True)
    push.add_argument('--tag', required=True)
    push.add_argument('--candidate', action='store_true')
    args = parser.parse_args()
    if args.command == 'build':
        OUTPUT.mkdir(parents=True, exist_ok=True)
        result = run(['docker', 'buildx', 'build', '--platform', args.platform, '--load', '-t', args.image,
                      '-f', 'agents/code-review/Dockerfile', '.'], timeout=300, check=False)
        (OUTPUT / 'build.log').write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError('Build failed; see build/review-image/build.log')
        print('Built', args.image, '; see build/review-image/build.log')
    elif args.command == 'audit':
        validate(args.image)
    elif args.command == 'scan':
        scan(args.image)
    else:
        publish(args.image, args.tag, args.candidate)


if __name__ == '__main__':
    main()