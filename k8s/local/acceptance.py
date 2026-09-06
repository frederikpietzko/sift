#!/usr/bin/env python3
"""Manual sample-PR acceptance gate. Requires a running operator and published identity-capable image."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
import urllib.request
import uuid

from dev import Cluster, NAMESPACE, ROOT, apply_review

sys.path.insert(0, str(ROOT / 'agents/code-review/image'))
from artifact import audit
from image import export, run


def sample_pr():
    request = urllib.request.Request('https://api.github.com/repos/frederikpietzko/ebfs-jpa/pulls/1',
                                     headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'sift-validation'})
    with urllib.request.urlopen(request, timeout=20) as response:
        value = json.load(response)
    if value['head']['repo']['clone_url'] != value['base']['repo']['clone_url']:
        raise RuntimeError('Sample PR moved to a fork; explicit checkout-contract review required')
    return {'repositoryUrl': value['base']['repo']['clone_url'], 'branch': value['head']['ref'],
            'baseBranch': value['base']['ref'], 'commitSha': value['head']['sha'], 'pullRequest': str(value['number'])}


def event_matches(event, spec, execution):
    return (isinstance(event, dict) and all(event.get(k) == v for k, v in spec.items())
            and event.get('executionId') == execution and isinstance(event.get('summary'), str)
            and isinstance(event.get('findings'), list) and isinstance(event.get('completedAt'), str))


def verify_resources(cluster, cr, image):
    status, metadata = cr.get('status', {}), cr['metadata']
    execution = f"{metadata['uid']}:{metadata['generation']}"
    if status.get('observedGeneration') != metadata['generation'] or status.get('executionId') != execution:
        return None
    if not status.get('jobRef') or not status.get('configMapRef'):
        return None
    resources = []
    for kind, key in [('job', 'jobRef'), ('configmap', 'configMapRef')]:
        resource = cluster.get(kind, status[key]['name'], '-n', NAMESPACE)
        if not resource or resource['metadata']['uid'] != status[key]['uid'] or not any(
                owner['uid'] == metadata['uid'] and owner.get('controller')
                for owner in resource['metadata'].get('ownerReferences', [])):
            raise RuntimeError('Owned resource identity mismatch')
        resources.append(resource)
    job, config = resources
    container = job['spec']['template']['spec']['containers'][0]
    if container['image'] != image:
        raise RuntimeError('Operator did not use the required published image')
    environment = {entry['name']: entry.get('value') for entry in container['env']}
    if (environment.get('SPRING_CONFIG_ADDITIONAL_LOCATION') != 'file:/etc/sift/review/application.yaml'
            or 'SPRING_CONFIG_LOCATION' in environment or 'SPRING_PROFILES_ACTIVE' in environment):
        raise RuntimeError('Unexpected configuration delivery')
    mounted = config['data']['application.yaml']
    if cr['spec']['commitSha'] not in mounted or execution not in mounted:
        raise RuntimeError('Mounted configuration does not carry the requested execution')
    if status.get('commitSha') != cr['spec']['commitSha']:
        raise RuntimeError('Status SHA does not match current execution')
    return job


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--timeout', type=int, default=3600)
    args = parser.parse_args()
    if not re.fullmatch(r'jbfpietzko/shift-code-review-agent@sha256:[0-9a-f]{64}', args.image):
        raise RuntimeError('Require published repository digest, not a local image/tag')
    if not 30 <= args.timeout <= 86400:
        raise RuntimeError('Timeout must be 30..86400 seconds')
    output = ROOT / 'build/review-acceptance'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'evidence.json').write_text(json.dumps({'result': 'IN_PROGRESS', 'image': args.image}) + '\n')
    spec = sample_pr()
    (output / 'requested-pr.json').write_text(json.dumps(spec, indent=2) + '\n')
    run(['docker', 'pull', args.image])
    with tempfile.TemporaryDirectory(dir=output) as temporary:
        contract = audit(export(args.image, Path(temporary)))
    if contract['missingIdentityMembers']:
        evidence = {'result': 'BLOCKED', 'image': args.image, 'requested': spec,
                    'missingIdentityMembers': contract['missingIdentityMembers'],
                    'reviewApplied': False, 'eventConsumed': False}
        (output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        raise RuntimeError('Packaged SHA/execution contract missing; no CR or review Job created')
    # Presence is not proof: this real gate still requires matching execution results, and the agent
    # owner must supply behavioral pinned-checkout/mismatched-SHA tests before attempting it.
    import pika
    password = os.environ.get('SIFT_VALIDATION_RABBITMQ_PASSWORD')
    if not password:
        raise RuntimeError('Set SIFT_VALIDATION_RABBITMQ_PASSWORD without putting it on the command line')
    cluster = Cluster(args.context)
    cluster.namespace()
    name = 'sample-pr-' + uuid.uuid4().hex[:12]
    events = []
    connection = pika.BlockingConnection(pika.ConnectionParameters(
        host='127.0.0.1', port=5672, virtual_host='/', heartbeat=60, socket_timeout=10,
        blocked_connection_timeout=10, credentials=pika.PlainCredentials('sift', password)))
    try:
        channel = connection.channel()
        channel.exchange_declare(exchange='sift.events', exchange_type='topic', durable=True)
        queue = channel.queue_declare(queue='', exclusive=True, auto_delete=True).method.queue
        channel.queue_bind(queue=queue, exchange='sift.events', routing_key='code-review.completed')

        def received(ch, method, properties, body):
            try:
                event = json.loads(body)
                if isinstance(event, dict):
                    events.append(event)
            except (ValueError, UnicodeError):
                pass
            ch.basic_ack(method.delivery_tag)

        channel.basic_consume(queue=queue, on_message_callback=received)
        print('Dedicated AMQP validation consumer bound before applying CR', flush=True)
        apply_review(cluster, name, spec['repositoryUrl'], spec['branch'], spec['baseBranch'],
                     spec['commitSha'], spec['pullRequest'])
        deadline = time.monotonic() + args.timeout
        while time.monotonic() < deadline:
            connection.process_data_events(time_limit=1)
            cr = cluster.get('codereview', name, '-n', NAMESPACE)
            if not cr:
                raise RuntimeError('Applied CodeReview disappeared')
            if cr['spec'] != spec:
                raise RuntimeError('Sample CR spec changed during acceptance')
            status = cr.get('status', {})
            if status.get('phase') == 'FAILED':
                raise RuntimeError('Review failed; inspect restricted CR/Pod logs (not a passing gate)')
            job = verify_resources(cluster, cr, args.image)
            execution = f"{cr['metadata']['uid']}:{cr['metadata']['generation']}"
            completed = job and any(c['type'] == 'Complete' and c['status'] == 'True'
                                    for c in job.get('status', {}).get('conditions', []))
            matching = next((e for e in events if event_matches(e, spec, execution)), None)
            if completed and status.get('phase') == 'SUCCESS' and matching:
                first = cr
                first_jobs = {j['metadata']['uid'] for j in cluster.get('jobs', '-n', NAMESPACE)['items']
                              if any(o['uid'] == cr['metadata']['uid'] for o in j['metadata'].get('ownerReferences', []))}
                apply_review(cluster, name, spec['repositoryUrl'], spec['branch'], spec['baseBranch'],
                             spec['commitSha'], spec['pullRequest'])
                for _ in range(10):
                    connection.process_data_events(time_limit=1)
                same = cluster.get('codereview', name, '-n', NAMESPACE)
                jobs = {j['metadata']['uid'] for j in cluster.get('jobs', '-n', NAMESPACE)['items']
                        if any(o['uid'] == cr['metadata']['uid'] for o in j['metadata'].get('ownerReferences', []))}
                if (same['metadata']['generation'] != first['metadata']['generation'] or jobs != first_jobs
                        or same['status']['jobRef'] != first['status']['jobRef']):
                    raise RuntimeError('Unchanged apply scheduled another execution')
                evidence = {'result': 'PASS', 'image': args.image, 'crName': name,
                            'crUid': cr['metadata']['uid'], 'generation': cr['metadata']['generation'],
                            'jobUid': job['metadata']['uid'], 'commitSha': spec['commitSha'],
                            'executionId': execution, 'phase': status['phase'], 'eventConsumed': True,
                            'eventIdentity': {k: matching[k] for k in (*spec, 'executionId')},
                            'unchangedApplyDidNotRerun': True}
                (output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
                print(json.dumps(evidence, indent=2))
                return
        raise RuntimeError('Timed out waiting for Job completion, current-generation SUCCESS and matching AMQP event')
    finally:
        connection.close()
        print('CR retained for inspection:', name)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        evidence_path = ROOT / 'build/review-acceptance/evidence.json'
        if evidence_path.exists():
            evidence = json.loads(evidence_path.read_text())
            if evidence.get('result') == 'IN_PROGRESS':
                evidence.update(result='FAILED', failureType=type(error).__name__)
                evidence_path.write_text(json.dumps(evidence, indent=2) + '\n')
        # Do not echo credential-bearing exceptions from the broker/client libraries.
        detail = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print('BLOCKED/FAILED:', detail, file=sys.stderr)
        sys.exit(1)