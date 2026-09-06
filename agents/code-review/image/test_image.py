import io
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from artifact import audit, inspect
from image import OUTPUT, filesystem_audit, publish

OUTPUT.mkdir(parents=True, exist_ok=True)


def jar(entries):
    result = io.BytesIO()
    with zipfile.ZipFile(result, 'w') as archive:
        for key, value in entries.items():
            archive.writestr(key, value)
    return result.getvalue()


class ImageTests(unittest.TestCase):
    def test_nested_secret_resources_are_rejected(self):
        for name in ('application-local.yaml', '.kubeconfig', '.env', 'private.key'):
            with self.subTest(name=name), zipfile.ZipFile(io.BytesIO(jar({
                'BOOT-INF/lib/shared.jar': jar({name: 'sensitive'})
            }))) as archive, self.assertRaises(RuntimeError):
                inspect(archive)

    def test_artifact_contract_missing_and_present(self):
        for fields in (b'', b'getCommitSha getExecutionId'):
            with tempfile.TemporaryDirectory(dir=OUTPUT) as temporary:
                path = Path(temporary) / 'agent.jar'
                path.write_bytes(jar({
                    'BOOT-INF/classes/application.yaml': 'spring.main.web-application-type: none',
                    'BOOT-INF/classes/org/sift/agents/review/ReviewProperties.class': fields,
                    'BOOT-INF/classes/org/sift/agents/review/ReviewAgent.class': b'ToolAllowlistAdvisor',
                    'BOOT-INF/lib/events-jvm.jar': jar({'org/sift/events/CodeReviewCompletedEvent.class': fields}),
                    'BOOT-INF/lib/shared-jvm.jar': jar({
                        'org/sift/agents/shared/advisors/ToolAllowlistAdvisor.class': b'advisor'}),
                }))
                result = audit(path)
                self.assertEqual(0 if fields else 4, len(result['missingIdentityMembers']))
                self.assertFalse(result['acceptanceReady'])

    def test_forbidden_executables_and_symlinks_rejected(self):
        for name in ('usr/bin/curl', 'usr/bin/wget', 'opt/java/openjdk/bin/jwebserver', 'root/.docker/config.json'):
            for kind in (tarfile.REGTYPE, tarfile.SYMTYPE):
                with self.subTest(name=name, kind=kind), tempfile.TemporaryDirectory(dir=OUTPUT) as temporary:
                    path = Path(temporary) / 'rootfs.tar'
                    with tarfile.open(path, 'w') as archive:
                        member = tarfile.TarInfo(name)
                        member.type = kind
                        archive.addfile(member)
                    with self.assertRaises(RuntimeError):
                        filesystem_audit(path)

    def test_missing_contract_blocks_release_before_registry_operations(self):
        with patch('image.validate', return_value={'missingIdentityMembers': ['properties.getCommitSha']}), \
                patch('image.run') as run, self.assertRaisesRegex(RuntimeError, 'BLOCKED'):
            publish('local', 'release-123', False)
        run.assert_not_called()

    def test_candidate_requires_honest_tag(self):
        with self.assertRaisesRegex(RuntimeError, 'candidate-'):
            publish('local', 'release-123', True)

    def test_existing_registry_tag_cannot_be_overwritten(self):
        with patch('image.validate', return_value={'missingIdentityMembers': []}), \
                patch('image.scan', return_value={}), \
                patch('image.run') as run, self.assertRaisesRegex(RuntimeError, 'overwrite'):
            run.return_value.returncode = 0
            publish('local', 'candidate-123', True)
        self.assertEqual(1, run.call_count)


if __name__ == '__main__':
    unittest.main()