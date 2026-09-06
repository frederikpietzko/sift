"""Offline safety and streaming tests: python3 -m unittest discover -s k8s/local -p 'test_*.py'."""
import asyncio
import contextlib
import io
import ipaddress
import subprocess
import unittest
from unittest.mock import Mock, patch

import dev
import host_relay


class HelperTest(unittest.TestCase):
    def test_context_mismatch_stops_before_api_or_mutation(self):
        with patch.object(dev.Path, "is_file", return_value=True), \
                patch.object(dev.Cluster, "call", return_value=Mock(stdout="kind-other\n")) as call:
            with self.assertRaisesRegex(RuntimeError, "Expected context"):
                dev.Cluster("kind-kind")
            call.assert_called_once_with("config", "current-context")

    def test_manifest_stream_decoder(self):
        cluster = object.__new__(dev.Cluster)
        cluster.call = Mock(return_value=Mock(stdout='{"kind":"Service"}\n{"kind":"List","items":[{"kind":"Role"}]}'))
        self.assertEqual([{"kind": "Service"}, {"kind": "Role"}], cluster.render("manifest.yaml"))

    def test_empty_lists_are_distinct_from_absent_named_resources(self):
        cluster = object.__new__(dev.Cluster)
        cluster.call = Mock(return_value=Mock(stdout=""))
        self.assertEqual({"items": []}, cluster.get("codereviews", "-n", "sift-dev"))
        self.assertIsNone(cluster.get("codereview", "absent", "-n", "sift-dev"))

    def test_unowned_object_is_not_adopted(self):
        cluster = object.__new__(dev.Cluster)
        cluster.get = Mock(return_value={"metadata": {"labels": {}}})
        with self.assertRaisesRegex(RuntimeError, "Refusing"):
            cluster.owned({"kind": "Secret", "metadata": {"name": "foreign"}})

    def test_api_errors_never_echo_credentials(self):
        cluster = object.__new__(dev.Cluster)
        cluster.base = ["kubectl"]
        with patch.object(dev.subprocess, "run", return_value=Mock(returncode=1, stderr="SENSITIVE")):
            with self.assertRaises(RuntimeError) as error:
                cluster.call("create", value={"data": "SENSITIVE"})
        self.assertNotIn("SENSITIVE", str(error.exception))

    def test_secrets_use_stdin_without_last_applied_or_console_values(self):
        cluster = Mock()
        cluster.owned.return_value = None
        output = io.StringIO()
        with patch.dict(dev.os.environ, {"OPENAI_API_KEY": "secret-model", "SIFT_MODEL_PROXY_TOKEN": "secret-proxy",
                                         "SPRING_RABBITMQ_PASSWORD": "secret-rabbit"}, clear=True), \
                contextlib.redirect_stdout(output):
            dev.secrets(cluster)
        call = cluster.call.call_args
        self.assertEqual(("create", "-f", "-"), call.args)
        self.assertNotIn("annotations", call.kwargs["value"]["metadata"])
        self.assertEqual({"model-api-key", "proxy-token", "rabbitmq-password"}, set(call.kwargs["value"]["data"]))
        self.assertNotIn("secret-", output.getvalue())

    def test_local_import_rejects_changed_model_path(self):
        with patch.object(dev.Path, "read_text", return_value='OPENAI_BASE_URL: "http://other/path"\n'):
            with self.assertRaisesRegex(RuntimeError, "path changed"):
                dev.local_credentials()

    def test_invalid_review_never_calls_apply(self):
        for sha, repository in [("short", "https://example.org/repo"), ("a" * 40, "https://token@example.org/repo")]:
            cluster = Mock()
            with self.assertRaises(RuntimeError):
                dev.apply_review(cluster, "review", repository, "branch", "main", sha)
            cluster.call.assert_not_called()

    def test_review_application_creates_only_cr(self):
        cluster = Mock()
        with contextlib.redirect_stdout(io.StringIO()):
            dev.apply_review(cluster, "review", "https://example.org/repo", "branch", "main", "a" * 40, "1")
        self.assertEqual("CodeReview", cluster.call.call_args.kwargs["value"]["kind"])
        self.assertEqual("1", cluster.call.call_args.kwargs["value"]["spec"]["pullRequest"])

    def test_relay_rejects_wildcard_bind(self):
        result = subprocess.run(["python3", str(dev.ROOT / "k8s/local/host_relay.py"), "--bind", "0.0.0.0",
                                 "--allow-client", "127.0.0.1/32", "--service", "model"], capture_output=True)
        self.assertNotEqual(0, result.returncode)

    def test_relay_mode_rejects_missing_or_loopback_interface_before_mutation(self):
        for address in [None, ipaddress.ip_address("127.0.0.1"), ipaddress.ip_address("0.0.0.0")]:
            cluster = Mock()
            with self.assertRaises(RuntimeError):
                dev.apply(cluster, relays=["model"], relay_address=address)
            cluster.call.assert_not_called()

    def test_noninteractive_secret_prompt_is_rejected(self):
        cluster = Mock()
        cluster.owned.return_value = None
        with patch.dict(dev.os.environ, {}, clear=True), patch.object(dev.sys.stdin, "isatty", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "Noninteractive"):
                dev.secrets(cluster)
        cluster.call.assert_not_called()

    def test_host_run_uses_root_kubeconfig_and_removes_raw_credentials(self):
        cluster = Mock()
        values = {"SIFT_REVIEW_IMAGE": "example.org/review:test", "OPENAI_API_KEY": "secret-model",
                  "SIFT_MODEL_PROXY_TOKEN": "secret-proxy", "SPRING_RABBITMQ_PASSWORD": "secret-rabbit"}
        with patch.dict(dev.os.environ, values, clear=True), patch.object(dev.os, "chdir"), \
                patch.object(dev.os, "execve") as execute, contextlib.redirect_stdout(io.StringIO()):
            dev.run_operator(cluster)
        command, arguments, environment = execute.call_args.args
        self.assertEqual(str(dev.ROOT / "kotlin"), command)
        self.assertEqual([command, "run", "--module", "operator"], arguments)
        self.assertEqual(str(dev.ROOT / ".kubeconfig"), environment["KUBECONFIG"])
        self.assertEqual((dev.ROOT / "k8s/local/operator.yaml").as_uri(), environment["SPRING_CONFIG_ADDITIONAL_LOCATION"])
        self.assertFalse({"OPENAI_API_KEY", "SIFT_MODEL_PROXY_TOKEN", "SPRING_RABBITMQ_PASSWORD"} & environment.keys())
        self.assertNotIn("SPRING_CONFIG_LOCATION", environment)
        self.assertNotIn("SPRING_PROFILES_ACTIVE", environment)

    def test_host_run_rejects_environment_that_can_bypass_validated_config(self):
        for key in ["SPRING_PROFILES_INCLUDE", "SPRING_APPLICATION_JSON", "KUBERNETES_MASTER"]:
            with patch.dict(dev.os.environ, {"SIFT_REVIEW_IMAGE": "example.org/review:test", key: "override"}, clear=True), \
                    patch.object(dev.os, "execve") as execute:
                with self.assertRaises(RuntimeError):
                    dev.run_operator(Mock())
                execute.assert_not_called()


class RelayTest(unittest.IsolatedAsyncioTestCase):
    async def test_path_and_delayed_stream_are_preserved_before_upstream_finishes(self):
        received = []
        first_received = asyncio.Event()
        done = asyncio.Event()

        async def upstream(reader, writer):
            try:
                received.append(await reader.readuntil(b"\r\n\r\n"))
                writer.write(b"data: first\n\n")
                await writer.drain()
                await asyncio.wait_for(first_received.wait(), timeout=2)
                await asyncio.sleep(0.05)
                writer.write(b"data: second\n\ndata: [DONE]\n\n")
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()
                done.set()

        server = await asyncio.start_server(upstream, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        relay_done = asyncio.Event()

        async def forward(reader, writer):
            try:
                await host_relay.forward(reader, writer, port, [ipaddress.ip_network("127.0.0.1/32")])
            finally:
                relay_done.set()

        relay = await asyncio.start_server(forward, "127.0.0.1", 0)
        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", relay.sockets[0].getsockname()[1])
            request = b"POST /wire/test-token/codex/openai/v1/chat/completions HTTP/1.1\r\nHost: jb-central\r\n\r\n"
            writer.write(request)
            await writer.drain()
            self.assertEqual(b"data: first\n\n", await asyncio.wait_for(reader.readuntil(b"\n\n"), 2))
            self.assertFalse(done.is_set())
            first_received.set()
            writer.write_eof()
            self.assertEqual(b"data: second\n\ndata: [DONE]\n\n", await asyncio.wait_for(reader.read(), 2))
            self.assertEqual([request], received)
            writer.close()
            await writer.wait_closed()
            await asyncio.wait_for(relay_done.wait(), 2)
        finally:
            relay.close()
            server.close()
            await relay.wait_closed()
            await server.wait_closed()

    async def test_disallowed_source_never_opens_upstream(self):
        writer = Mock()
        writer.get_extra_info.return_value = ("192.0.2.1", 1000)
        writer.wait_closed = unittest.mock.AsyncMock()
        with patch.object(host_relay.asyncio, "open_connection") as connect:
            await host_relay.forward(Mock(), writer, 19516, [ipaddress.ip_network("127.0.0.1/32")])
            connect.assert_not_called()
        writer.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()