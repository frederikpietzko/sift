"""Real-API lifecycle scenarios used by provisioning_api.py --lifecycle.

No review image runs: an unscheduled, finalizer-held Pod exercises actual Kubernetes
foreground GC. Job status fixtures exercise projection, not the final review E2E gate.
"""
import copy
from datetime import datetime, timezone
import json
import time


def run(kubectl, items, wait_for, namespace, review, job, restart_operator):
    uid = job["metadata"]["ownerReferences"][0]["uid"]
    old_name = job["metadata"]["name"]

    def primary():
        return next(item for item in items("codereviews") if item["metadata"]["uid"] == uid)

    def owned(kind):
        return [item for item in items(kind) if any(owner["uid"] == uid
                for owner in item["metadata"].get("ownerReferences", []))]

    def patch(kind, name, value, status=False):
        extra = ["--subresource=status"] if status else []
        return kubectl("-n", namespace, "patch", kind, name, "--type=merge", "-p", json.dumps(value), *extra)

    def reason():
        return primary().get("status", {}).get("conditions", [{}])[0].get("reason")

    def assert_held():
        assert [item["metadata"]["name"] for item in owned("jobs")] == [old_name]
        assert [item["metadata"]["name"] for item in owned("configmaps")] == [old_name]

    pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {
        "name": "held-old-execution", "namespace": namespace,
        "labels": job["spec"]["template"]["metadata"]["labels"],
        "finalizers": ["sift.org/lifecycle-test"],
        "ownerReferences": [{"apiVersion": "batch/v1", "kind": "Job", "name": old_name,
                             "uid": job["metadata"]["uid"], "controller": True, "blockOwnerDeletion": True}]},
        "spec": {"nodeSelector": {"sift.org/test-never-schedule": "true"},
                 "restartPolicy": "Never", "automountServiceAccountToken": False,
                 "containers": [{"name": "review", "image": "registry.example.invalid/never-run:test"}]}}
    kubectl("create", "-f", "-", value=pod)
    wait_for(lambda: reason() == "SchedulingFailed")
    # The API rejects status based on an old resourceVersion after a spec update.
    stale = copy.deepcopy(primary())
    patch("codereview", "provision", {"spec": {"commitSha": "b" * 40}})
    stale["status"]["phase"] = "SUCCESS"
    assert kubectl("replace", "--subresource=status", "-f", "-", value=stale, check=False).returncode != 0
    wait_for(lambda: reason() == "CancellationInProgress")
    wait_for(lambda: owned("jobs")[0]["metadata"].get("deletionTimestamp"))
    assert_held()
    patch("codereview", "provision", {"spec": {"commitSha": "c" * 40}})
    wait_for(lambda: primary().get("status", {}).get("observedGeneration") == 3)
    assert_held()
    assert owned("jobs")[0]["metadata"]["finalizers"] == ["foregroundDeletion"]
    # A late old-generation failure must never replace the new cancellation status.
    patch("job", old_name, {"status": {"active": 0, "ready": 0, "terminating": 0,
          "conditions": [{"type": kind, "status": "True", "reason": "BackoffLimitExceeded",
                          "lastTransitionTime": "2026-09-06T00:00:00Z"}
                         for kind in ("FailureTarget", "Failed")]}}, status=True)
    time.sleep(1)
    assert reason() == "CancellationInProgress"
    assert primary()["status"]["observedGeneration"] == 3
    try:
        patch("pod", pod["metadata"]["name"], {"metadata": {"finalizers": []}})
        kubectl("-n", namespace, "delete", "pod", pod["metadata"]["name"], "--wait=false", "--ignore-not-found")
        wait_for(lambda: len(owned("jobs")) == 1 and owned("jobs")[0]["metadata"]["name"].endswith("-g3"))
        wait_for(lambda: primary().get("status", {}).get("jobRef", {}).get("name", "").endswith("-g3"))
        assert len(owned("configmaps")) == 1
        current = owned("jobs")[0]
        assert current["metadata"]["uid"] != job["metadata"]["uid"]
        assert primary()["status"]["executionId"] == uid + ":3"
        assert primary()["status"]["commitSha"] == "c" * 40
        # Deleting a known nonterminal Job is failure, never a new execution.
        kubectl("-n", namespace, "delete", "job", current["metadata"]["name"], "--wait=false")
        wait_for(lambda: reason() == "ResourcesLost")
        terminal = copy.deepcopy(primary()["status"])
        kubectl("-n", namespace, "delete", "configmap", current["metadata"]["name"], "--wait=false")
        kubectl("apply", "-f", "-", value={**review, "spec": {**review["spec"], "commitSha": "c" * 40}})
        time.sleep(6)
        assert not owned("jobs") and not owned("configmaps")
        assert primary()["status"] == terminal
        # A new generation after FAILED is allowed. Complete only via Job condition.
        patch("codereview", "provision", {"spec": {"commitSha": "d" * 40}})
        wait_for(lambda: len(owned("jobs")) == 1)
        newest = owned("jobs")[0]
        completed_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        patch("job", newest["metadata"]["name"], {"status": {
            "completionTime": completed_at, "succeeded": 1,
            "conditions": [{"type": kind, "status": "True", "lastTransitionTime": completed_at}
                           for kind in ("SuccessCriteriaMet", "Complete")]}}, status=True)
        wait_for(lambda: primary().get("status", {}).get("phase") == "SUCCESS")
        terminal = copy.deepcopy(primary()["status"])
        assert terminal["observedGeneration"] == 4
        kubectl("-n", namespace, "delete", "job,configmap", newest["metadata"]["name"], "--wait=false")
        # Metadata-only events trigger reconciliation without changing generation.
        patch("codereview", "provision", {"metadata": {"annotations": {"test": "terminal"}}})
        restart_operator()
        time.sleep(6)
        assert not owned("jobs") and not owned("configmaps")
        assert primary()["status"] == terminal
        # Leave an owned run to verify CR deletion/GC in the shared harness.
        patch("codereview", "provision", {"spec": {"commitSha": "e" * 40}})
        wait_for(lambda: len(owned("jobs")) == 1)
        print("PASS: foreground cancellation holds ConfigMap until Pod deletion; g2 coalesces to g3;")
        print("      stale status CAS rejected; stale Job outcome ignored; lost work not retried;")
        print("      FAILED/SUCCESS survive child deletion; new generations after terminal states execute")
        print("      restart recovers children without status and preserves terminal identities without children")
        return owned("jobs")[0]
    finally:
        # Only the fixture finalizer is removed; do not leave a test namespace stuck terminating.
        kubectl("-n", namespace, "patch", "pod", pod["metadata"]["name"], "--type=merge",
                "-p", json.dumps({"metadata": {"finalizers": []}}), check=False)