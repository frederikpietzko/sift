"""Recursive executable-artifact audit. Identity presence is necessary, not proof of SHA-pinned checkout."""
import argparse
import io
import json
from pathlib import Path
import zipfile

CLASSES = {
    'properties': 'org/sift/agents/review/ReviewProperties.class',
    'event': 'org/sift/events/CodeReviewCompletedEvent.class',
    'agent': 'org/sift/agents/review/ReviewAgent.class',
    'advisor': 'org/sift/agents/shared/advisors/ToolAllowlistAdvisor.class',
}


def inspect(archive, classes=None):
    classes = {} if classes is None else classes
    for name in archive.namelist():
        leaf = Path(name).name.lower()
        if (leaf.startswith('application-') or leaf in ('.kubeconfig', '.env', 'config.json')
                or leaf.endswith(('.pem', '.key', '.p12', '.pfx'))):
            raise RuntimeError(f'Forbidden packaged resource: {name}')
        for key, suffix in CLASSES.items():
            if name.endswith(suffix):
                classes[key] = archive.read(name)
        if name.endswith('.jar'):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                inspect(nested, classes)
    return classes


def audit(path):
    with zipfile.ZipFile(path) as archive:
        classes = inspect(archive)
        for name in ('events', 'shared'):
            if f'BOOT-INF/lib/{name}-jvm.jar' not in archive.namelist():
                raise RuntimeError('Missing nested module jar')
        if 'BOOT-INF/classes/application.yaml' not in archive.namelist():
            raise RuntimeError('Missing packaged defaults')
    if 'advisor' not in classes:
        raise RuntimeError('Integrated advisor absent')
    if b'ToolAllowlistAdvisor' not in classes.get('agent', b''):
        raise RuntimeError('Agent does not reference advisor')
    missing = [f'{key}.{field.decode()}' for key in ('properties', 'event')
               for field in (b'getCommitSha', b'getExecutionId') if field not in classes.get(key, b'')]
    return {'advisorPresentAndReferenced': True, 'localResourcesAbsent': True,
            'missingIdentityMembers': missing, 'acceptanceReady': False,
            'note': 'Field presence cannot prove pinned checkout; require behavioral and E2E evidence.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifact', type=Path)
    parser.add_argument('--require-identity', action='store_true')
    args = parser.parse_args()
    result = audit(args.artifact)
    print(json.dumps(result, indent=2))
    if args.require_identity and result['missingIdentityMembers']:
        raise SystemExit('BLOCKED: packaged agent/event identity contract is missing')


if __name__ == '__main__':
    main()