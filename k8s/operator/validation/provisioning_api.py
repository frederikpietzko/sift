#!/usr/bin/env python3
"""Explicit real-kind provisioning test. Creates only a temporary namespace and, if absent, the CRD."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, help="Expected existing kind context")
    parser.add_argument("--lifecycle", action="store_true", help="Also test generation replacement and durable outcomes")
    args = parser.parse_args()
    base = ["kubectl", "--kubeconfig", str(ROOT / ".kubeconfig"), "--context", args.context, "--request-timeout=10s"]

    def kubectl(*arguments, value=None, check=True):
        result = subprocess.run(base + list(arguments), input=json.dumps(value) if value else None,
                                text=True, capture_output=True, timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr)
        return result

    current = subprocess.check_output(base[:3] + ["config", "current-context"], text=True).strip()
    if current != args.context or not current.startswith("kind-"):
        raise RuntimeError("Kubeconfig must select the explicitly expected kind context")
    kubectl("get", "nodes")
    namespace = "sift-provision-test-" + uuid.uuid4().hex[:8]
    crd_absent = not kubectl("get", "crd", "codereviews.sift.org", "--ignore-not-found", "-o", "name").stdout.strip()
    if crd_absent:
        kubectl("create", "-f", str(ROOT / "k8s/manifests/crds/codereviews.sift.org-v1.yml"))
        kubectl("wait", "--for=condition=Established", "crd/codereviews.sift.org", "--timeout=15s")
    kubectl("create", "namespace", namespace)
    process = None
    output = ROOT / "build" / "operator-api-validation"
    output.mkdir(parents=True, exist_ok=True)
    log = output / "operator.log"
    image = "registry.example.invalid/review:provisioning-test"
    env = os.environ.copy()
    env.update(KUBECONFIG=str(ROOT / ".kubeconfig"), SIFT_OPERATOR_NAMESPACE=namespace, SIFT_REVIEW_IMAGE=image)

    def start(stream):
        return subprocess.Popen([str(ROOT / "kotlin"), "run", "--module", "operator"], cwd=ROOT,
                                env=env, stdout=stream, stderr=subprocess.STDOUT)

    def stop():
        if process is not None:
            process.terminate()
            process.wait(timeout=20)

    def items(kind):
        return json.loads(kubectl("-n", namespace, "get", kind, "-o", "json").stdout)["items"]

    def wait_for(predicate):
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            if predicate():
                return
            if process is not None and process.poll() is not None:
                raise RuntimeError(f"Operator exited; inspect {log}")
            time.sleep(0.25)
        raise RuntimeError(f"Timed out; inspect {log}")

    try:
        # No review ServiceAccount: Kubernetes validates Jobs but does not launch an image or external calls.
        review = {"apiVersion": "sift.org/v1alpha1", "kind": "CodeReview",
                  "metadata": {"name": "provision", "namespace": namespace},
                  "spec": {"repositoryUrl": "https://example.org/repository.git", "branch": "feature",
                           "baseBranch": "main", "commitSha": "a" * 40, "pullRequest": "1"}}
        invalid = json.loads(json.dumps(review))
        invalid["spec"]["commitSha"] = "invalid"
        assert kubectl("create", "-f", "-", value=invalid, check=False).returncode != 0
        with log.open("w") as stream:
            process = start(stream)
            kubectl("apply", "-f", "-", value=review)
            wait_for(lambda: len(items("jobs")) == 1 and len(items("configmaps")) >= 1)
            job = items("jobs")[0]
            config_name = job["spec"]["template"]["spec"]["volumes"][0]["configMap"]["name"]
            config = json.loads(kubectl("-n", namespace, "get", "configmap", config_name, "-o", "json").stdout)
            primary = items("codereviews")[0]
            assert job["metadata"]["ownerReferences"][0]["uid"] == primary["metadata"]["uid"]
            assert config["metadata"]["ownerReferences"][0]["uid"] == primary["metadata"]["uid"]
            assert int(config["metadata"]["resourceVersion"]) < int(job["metadata"]["resourceVersion"])
            assert config["immutable"] is True
            wait_for(lambda: items("codereviews")[0].get("status", {}).get("jobRef", {}).get("uid") == job["metadata"]["uid"])
            assert items("codereviews")[0]["status"]["phase"] == "PENDING"
            # The real API, unlike CRUD mocks, rejects changes to execution snapshots.
            change = {"spec": {"template": {"spec": {"containers": [{"name": "review", "image": "other:tag"}]}}}}
            assert kubectl("-n", namespace, "patch", "job", job["metadata"]["name"], "--type=merge",
                           "-p", json.dumps(change), check=False).returncode != 0
            assert kubectl("-n", namespace, "patch", "configmap", config_name, "--type=merge",
                           "-p", json.dumps({"data": {"application.yaml": "changed: true"}}), check=False).returncode != 0
            kubectl("apply", "-f", "-", value=review)
            stop()
            if args.lifecycle:
                # Simulate successful child creation followed by a lost status write before restart.
                kubectl("-n", namespace, "patch", "codereview", "provision", "--subresource=status", "--type=merge",
                        "-p", json.dumps({"status": None}))
            env["SIFT_REVIEW_IMAGE"] = "registry.example.invalid/review@sha256:" + "b" * 64
            process = start(stream)
            # Bounded observation after restart, followed by a new CR proves the new process reconciles.
            second = json.loads(json.dumps(review))
            second["metadata"]["name"] = "second"
            kubectl("apply", "-f", "-", value=second)
            wait_for(lambda: len(items("jobs")) == 2)
            preserved = next(item for item in items("jobs") if item["metadata"]["uid"] == job["metadata"]["uid"])
            assert preserved["spec"] == job["spec"]
            newest = next(item for item in items("jobs") if item["metadata"]["uid"] != job["metadata"]["uid"])
            assert newest["spec"]["template"]["spec"]["containers"][0]["image"] == env["SIFT_REVIEW_IMAGE"]
            if args.lifecycle:
                from lifecycle_api import run
                def restart_operator():
                    nonlocal process
                    stop()
                    process = start(stream)
                wait_for(lambda: next(item for item in items("codereviews") if item["metadata"]["name"] == "provision")
                         .get("status", {}).get("jobRef", {}).get("uid") == job["metadata"]["uid"])
                job = run(kubectl, items, wait_for, namespace, review, job, restart_operator)
                config_name = job["metadata"]["name"]
            kubectl("-n", namespace, "delete", "codereview", "provision", "--wait=false")
            wait_for(lambda: all(item["metadata"]["uid"] != job["metadata"]["uid"] for item in items("jobs")))
            wait_for(lambda: all(item["metadata"]["name"] != config_name for item in items("configmaps")))
            assert len(items("jobs")) == 1
            print("PASS: kind API admission, SDK ConfigMap-before-Job ordering, owned immutable snapshots,")
            print("      unchanged apply, restart/image-change preservation, new execution image, owner garbage collection")
    finally:
        stop()
        if args.lifecycle:
            kubectl("-n", namespace, "patch", "pod", "held-old-execution", "--type=merge",
                    "-p", json.dumps({"metadata": {"finalizers": []}}), check=False)
        kubectl("delete", "namespace", namespace, "--wait=false")
        # The generated CRD is intentionally retained: deleting it is cluster-wide and can destroy other CRs.
        if crd_absent:
            print("Installed generated codereviews.sift.org CRD; retained for subsequent steps")


if __name__ == "__main__":
    main()