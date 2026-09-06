"""Runs inside a temporary validation Pod, never inside the review image."""
import http.client
import json
import os
import socket
import sys
import time
from urllib.parse import quote


def main():
    for service, port in [("jb-central", 19516), ("searxng", 8888), ("rabbitmq", 5672)]:
        socket.getaddrinfo(service, port)
        try:
            with socket.create_connection(("host.docker.internal", port), timeout=3):
                print(f"PASS: direct Pod-to-host TCP {port}", flush=True)
        except OSError:
            print(f"UNREACHABLE: direct Pod-to-host TCP {port}; fixed host relay may be required", flush=True)
        with socket.create_connection((service, port), timeout=5):
            print(f"PASS: Service DNS/TCP {service}:{port}", flush=True)
    connection = http.client.HTTPConnection("searxng", 8888, timeout=10)
    connection.request("GET", "/")
    response = connection.getresponse()
    if response.status != 200 or not response.read():
        raise RuntimeError("SearXNG HTTP probe failed")
    connection.close()
    print("PASS: SearXNG HTTP", flush=True)
    with socket.create_connection(("rabbitmq", 5672), timeout=5) as broker:
        broker.sendall(b"AMQP\x00\x00\x09\x01")
        if broker.recv(8)[:1] != b"\x01":
            raise RuntimeError("RabbitMQ AMQP handshake failed")
    print("PASS: RabbitMQ AMQP handshake (not authenticated event consumption)", flush=True)
    if "--model" in sys.argv:
        token = quote(os.environ["SIFT_MODEL_PROXY_TOKEN"], safe="")
        connection = http.client.HTTPConnection("jb-central", 19516, timeout=90)
        body = json.dumps({"model": "gpt-5.6-luna", "stream": True, "stream_options": {"include_usage": True},
                           "messages": [{"role": "user", "content": "Reply with only the word OK."}]})
        start = time.monotonic()
        connection.request("POST", f"/wire/{token}/codex/openai/v1/chat/completions", body,
                           {"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})
        response = connection.getresponse()
        if response.status != 200 or "text/event-stream" not in response.getheader("Content-Type", ""):
            print(f"Model HTTP status: {response.status}; SSE content type: "
                  f"{'text/event-stream' in response.getheader('Content-Type', '')}", flush=True)
            error_body = response.read(16384).decode(errors="replace").lower()
            for diagnostic in ["unsupported", "model", "reasoning", "temperature", "store", "required",
                               "not supported", "instructions", "host", "invalid", "messages", "stream"]:
                if diagnostic in error_body:
                    print(f"Model diagnostic keyword: {diagnostic}", flush=True)
            raise RuntimeError("Model path/auth/stream response failed")
        chunks, done, first = 0, False, None
        while time.monotonic() - start < 100:
            line = response.readline()
            if not line:
                break
            if line.startswith(b"data:"):
                first = first or time.monotonic()
                if line.strip() == b"data: [DONE]":
                    done = True
                    break
                data = json.loads(line[5:])
                if "error" in data:
                    raise RuntimeError("Model stream error")
                chunks += 1
        connection.close()
        if not done or chunks < 2:
            raise RuntimeError("Incomplete model event stream")
        print(f"PASS: model authenticated path, {chunks} SSE chunks and DONE; first event in {first - start:.2f}s", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAILED: upstream protocol/path/auth/stream check ({type(error).__name__}; "
              "response and credentials suppressed)", flush=True)
        sys.exit(1)