#!/usr/bin/env python3
"""Local-config/operator scheduling integration. Uses an unpullable image; never executes a review."""
import argparse
import os
import subprocess
import time
import uuid

from dev import Cluster, NAMESPACE, ROOT, apply_review, observe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    args = parser.parse_args()
    cluster = Cluster(args.context)
    cluster.namespace()
    if cluster.get("codereviews", "-n", NAMESPACE)["items"]:
        raise RuntimeError("Use an idle sift-dev namespace and stop any existing host operator first")
    name = "local-scheduling-" + uuid.uuid4().hex[:8]
    image = "registry.example.invalid/sift/local-scheduling:never-run"
    env = os.environ.copy()
    env["SIFT_REVIEW_IMAGE"] = image
    output = ROOT / "build/local-validation"
    output.mkdir(parents=True, exist_ok=True)
    with (output / "operator.log").open("w") as log:
        process = subprocess.Popen(["python3", str(ROOT / "k8s/local/dev.py"), "--context", args.context, "run"],
                                   cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        created = False
        try:
            apply_review(cluster, name, "https://example.org/not-a-real-review.git", "feature", "main", "a" * 40)
            created = True
            observe(cluster, name)
            first = cluster.get("codereview", name, "-n", NAMESPACE)
            status = first["status"]
            job = cluster.get("job", status["jobRef"]["name"], "-n", NAMESPACE)
            config = cluster.get("configmap", status["configMapRef"]["name"], "-n", NAMESPACE)
            container = job["spec"]["template"]["spec"]["containers"][0]
            assert container["image"] == image
            assert "http://jb-central:19516/wire/${SIFT_MODEL_PROXY_TOKEN}/codex/openai/v1" in config["data"]["application.yaml"]
            assert "http://searxng:8888" in config["data"]["application.yaml"]
            assert "spring.rabbitmq.host: rabbitmq" in config["data"]["application.yaml"]
            assert {e["name"] for e in container["env"] if "valueFrom" in e} >= {
                "OPENAI_API_KEY", "SIFT_MODEL_PROXY_TOKEN", "SPRING_RABBITMQ_PASSWORD"}
            apply_review(cluster, name, "https://example.org/not-a-real-review.git", "feature", "main", "a" * 40)
            time.sleep(6)
            same = cluster.get("codereview", name, "-n", NAMESPACE)
            assert same["metadata"]["generation"] == first["metadata"]["generation"]
            assert same["status"]["jobRef"] == status["jobRef"]
            print("PASS: host helper wiring, exact trusted image, mounted local endpoints, Secret references, unchanged apply")
            print("Image intentionally cannot run; no review outcome or event-consumption claim")
        finally:
            if created:
                cluster.call("delete", "codereview", name, "-n", NAMESPACE, "--wait=false")
            process.terminate()
            process.wait(timeout=20)


if __name__ == "__main__":
    main()