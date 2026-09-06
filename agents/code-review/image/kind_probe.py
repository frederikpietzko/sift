#!/usr/bin/env python3
"""Pull published image in kind and exercise tools with the review Job's security context; NOT a review."""
import argparse
import base64
import json
from pathlib import Path
import re
import sys
import tempfile
import time
import uuid
import zipfile

from image import OUTPUT, ROOT, export, run

sys.path.insert(0, str(ROOT / 'k8s/local'))
from dev import Cluster, NAMESPACE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--context', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'jbfpietzko/shift-code-review-agent@sha256:[0-9a-f]{64}', args.image):
        raise RuntimeError('Require a published image digest')
    cluster = Cluster(args.context)
    cluster.namespace()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=OUTPUT) as temporary:
        work = Path(temporary)
        run(['docker', 'pull', args.image])
        artifact = export(args.image, work)
        libraries, classes = work / 'lib', work / 'classes'
        libraries.mkdir()
        classes.mkdir()
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (libraries / Path(name).name).write_bytes(archive.read(name))
        run(['javac', '-cp', str(libraries / '*'), '-d', classes,
             ROOT / 'agents/code-review/image/ToolProbe.java'])
        probe = work / 'probe.jar'
        with zipfile.ZipFile(probe, 'w') as archive:
            archive.write(classes / 'ToolProbe.class', 'ToolProbe.class')
        name = 'image-probe-' + uuid.uuid4().hex[:12]
        metadata = {'name': name, 'namespace': NAMESPACE}
        config = {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': metadata,
                  'binaryData': {'probe.jar': base64.b64encode(probe.read_bytes()).decode()}}
        pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': metadata, 'spec': {
            'restartPolicy': 'Never', 'activeDeadlineSeconds': 120, 'serviceAccountName': 'sift-review',
            'automountServiceAccountToken': False,
            'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001, 'runAsGroup': 10001,
                                'fsGroup': 10001, 'seccompProfile': {'type': 'RuntimeDefault'}},
            'containers': [{'name': 'probe', 'image': args.image, 'imagePullPolicy': 'Always',
                            'workingDir': '/scratch',
                            'command': ['/opt/java/openjdk/bin/java'],
                            'args': ['-Dloader.path=/probe/probe.jar', '-Dloader.main=ToolProbe', '-cp',
                                     '/app/agent.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                            'securityContext': {'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True,
                                                'capabilities': {'drop': ['ALL']}},
                            'resources': {'requests': {'cpu': '500m', 'memory': '512Mi'},
                                          'limits': {'cpu': '2', 'memory': '2Gi'}},
                            'volumeMounts': [{'name': 'probe', 'mountPath': '/probe', 'readOnly': True},
                                             {'name': 'scratch', 'mountPath': '/scratch'},
                                             {'name': 'scratch', 'mountPath': '/tmp'}]}],
            'volumes': [{'name': 'probe', 'configMap': {'name': name, 'defaultMode': 292}},
                        {'name': 'scratch', 'emptyDir': {'sizeLimit': '2Gi'}}]}}
        created = []
        try:
            for value in (config, pod):
                cluster.call('create', '-f', '-', value=value)
                created.append(value['kind'])
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                actual = cluster.get('pod', name, '-n', NAMESPACE)
                phase = actual.get('status', {}).get('phase')
                if phase in ('Succeeded', 'Failed'):
                    logs = cluster.call('logs', name, '-n', NAMESPACE).stdout
                    if phase != 'Succeeded' or 'PACKAGED_TOOLS_OK' not in logs:
                        raise RuntimeError('Image Pod probe failed: ' + logs)
                    evidence = {'result': 'PASS', 'image': args.image, 'podUid': actual['metadata']['uid'],
                                'imageId': actual['status']['containerStatuses'][0]['imageID'],
                                'phase': phase, 'runtimeDefaultSeccomp': True, 'reviewExecuted': False}
                    (OUTPUT / 'kind-probe.json').write_text(json.dumps(evidence, indent=2) + '\n')
                    print(json.dumps(evidence, indent=2))
                    return
                time.sleep(1)
            raise RuntimeError('Published image Pod did not finish within 120s')
        finally:
            for kind in reversed(created):
                cluster.call('delete', kind, name, '-n', NAMESPACE, '--wait=false', check=False)


if __name__ == '__main__':
    main()