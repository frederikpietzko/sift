package org.sift.server.api

import org.sift.server.PostgresIntegrationTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the committed API contract `api/openapi.yaml` against drift: the description springdoc renders from the
 * live controllers must equal the file byte for byte (after dropping the request-dependent `servers` block).
 * A mismatch fails with a unified-style diff; run with `SIFT_UPDATE_OPENAPI=true` to rewrite the file instead
 * and commit the result together with the controller change.
 */
@AutoConfigureMockMvc
class OpenApiContractTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `openapi description is served anonymously and matches the committed contract`() {
        val actual = mockMvc.get("/v3/api-docs.yaml")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
            .let(::normalise)

        val contract = contractFile()
        if (System.getenv(UPDATE_ENV).toBoolean()) {
            Files.createDirectories(contract.parent)
            contract.writeText(actual)
            return
        }
        assertTrue(contract.exists(), "$contract is missing; generate it with $UPDATE_ENV=true")

        val expected = normalise(contract.readText())
        if (expected != actual) {
            fail(
                "$contract is out of date with the controllers. Regenerate with `$UPDATE_ENV=true ./kotlin test " +
                    "-m server` and commit the result.\n\n" + unifiedDiff(expected.lines(), actual.lines()),
            )
        }
    }

    companion object {
        const val UPDATE_ENV = "SIFT_UPDATE_OPENAPI"
        private const val CONTEXT_LINES = 3

        /** Walks up from the working directory to the repository root (`project.yaml`), so the test runs from anywhere. */
        fun contractFile(): Path {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .firstOrNull { it.resolve("project.yaml").exists() }
                ?: fail("Could not locate the repository root (project.yaml) above ${Path.of("").toAbsolutePath()}")
            return root.resolve("api").resolve("openapi.yaml")
        }

        /** Drops the `servers` block (it echoes the request's host and port) and unifies trailing whitespace. */
        fun normalise(yaml: String): String {
            val lines = yaml.lines().map { it.trimEnd() }
            val kept = mutableListOf<String>()
            var skipping = false
            for (line in lines) {
                if (line == "servers:") {
                    skipping = true
                    continue
                }
                if (skipping && line.isNotEmpty() && !line.first().isWhitespace() && !line.startsWith("-")) {
                    skipping = false
                }
                if (!skipping) kept += line
            }
            return kept.joinToString("\n").trimEnd() + "\n"
        }

        /** Minimal LCS-based unified diff (`-` committed, `+` generated) with [CONTEXT_LINES] lines of context. */
        fun unifiedDiff(expected: List<String>, actual: List<String>): String {
            val lcs = Array(expected.size + 1) { IntArray(actual.size + 1) }
            for (i in expected.indices.reversed()) {
                for (j in actual.indices.reversed()) {
                    lcs[i][j] = if (expected[i] == actual[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
            val ops = mutableListOf<Pair<Char, String>>()
            var i = 0
            var j = 0
            while (i < expected.size || j < actual.size) {
                when {
                    i < expected.size && j < actual.size && expected[i] == actual[j] -> ops += ' ' to expected[i++].also { j++ }
                    i < expected.size && (j == actual.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> ops += '-' to expected[i++]
                    else -> ops += '+' to actual[j++]
                }
            }
            val changed = ops.indices.filter { ops[it].first != ' ' }
            val shown = changed.flatMap { (it - CONTEXT_LINES)..(it + CONTEXT_LINES) }.filter { it in ops.indices }.toSortedSet()
            val out = StringBuilder("--- api/openapi.yaml (committed)\n+++ /v3/api-docs.yaml (generated)\n")
            var previous = -1
            for (index in shown) {
                if (index != previous + 1) out.append("@@ line ${index + 1} @@\n")
                out.append(ops[index].first).append(ops[index].second).append('\n')
                previous = index
            }
            return out.toString()
        }
    }
}
