import unittest
from unittest.mock import Mock

from acceptance import event_matches, verify_resources


class AcceptanceTests(unittest.TestCase):
    def test_requires_full_matching_structured_identity(self):
        spec = {'repositoryUrl': 'https://example.org/repo', 'branch': 'feature', 'baseBranch': 'main',
                'commitSha': 'a' * 40, 'pullRequest': '1'}
        event = dict(spec, executionId='uid:7', summary='No findings', findings=[], completedAt='2026-09-06T00:00:00Z')
        self.assertTrue(event_matches(event, spec, 'uid:7'))
        for field in (*spec, 'executionId', 'summary', 'findings', 'completedAt'):
            with self.subTest(field=field):
                self.assertFalse(event_matches({k: v for k, v in event.items() if k != field}, spec, 'uid:7'))
        self.assertFalse(event_matches(dict(event, executionId='uid:6'), spec, 'uid:7'))
        self.assertFalse(event_matches(dict(event, commitSha='b' * 40), spec, 'uid:7'))
        self.assertFalse(event_matches(dict(event, findings='not structured'), spec, 'uid:7'))

    def test_owned_current_generation_resources_and_configuration_required(self):
        cr = {'metadata': {'uid': 'cr-uid', 'generation': 7}, 'spec': {'commitSha': 'a' * 40},
              'status': {'observedGeneration': 7, 'executionId': 'cr-uid:7', 'commitSha': 'a' * 40,
                         'jobRef': {'name': 'job', 'uid': 'job-uid'},
                         'configMapRef': {'name': 'config', 'uid': 'config-uid'}}}
        metadata = {'ownerReferences': [{'uid': 'cr-uid', 'controller': True}]}
        job = {'metadata': dict(metadata, uid='job-uid'), 'spec': {'template': {'spec': {'containers': [
            {'image': 'published-image', 'env': [{'name': 'SPRING_CONFIG_ADDITIONAL_LOCATION',
                                               'value': 'file:/etc/sift/review/application.yaml'}]}]}}}}
        config = {'metadata': dict(metadata, uid='config-uid'),
                  'data': {'application.yaml': 'sift.review.commit-sha: ' + 'a' * 40 + '\nsift.review.execution-id: cr-uid:7'}}
        cluster = Mock()
        cluster.get.side_effect = lambda kind, *args: job if kind == 'job' else config
        self.assertIs(job, verify_resources(cluster, cr, 'published-image'))
        with self.assertRaisesRegex(RuntimeError, 'image'):
            verify_resources(cluster, cr, 'different-image')
        job['metadata']['uid'] = 'foreign-uid'
        with self.assertRaisesRegex(RuntimeError, 'identity'):
            verify_resources(cluster, cr, 'published-image')
        cr['status']['observedGeneration'] = 6
        self.assertIsNone(verify_resources(cluster, cr, 'published-image'))


if __name__ == '__main__':
    unittest.main()