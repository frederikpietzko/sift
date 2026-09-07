package org.sift.e2e

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Credentials the harness forwards into the cluster Secret; values are never logged. */
data class Credentials(val openAiApiKey: String, val modelProxyToken: String)

/** Everything the environment bootstrap needs to know before touching Docker or kind. */
data class PreflightReport(val credentials: Credentials, val serverPort: Int, val warnings: List<String>)

/**
 * Fail-fast checks with actionable messages. Runs before any side effect so a missing tool or
 * credential never leaves a half-created cluster behind.
 */
object Preflight {
    const val OPENAI_API_KEY = "OPENAI_API_KEY"
    const val MODEL_PROXY_TOKEN = "SIFT_MODEL_PROXY_TOKEN"
    const val MODEL_PROXY_PORT = 19516
    const val SEARXNG_PORT = 8888
    private const val PROBE_TIMEOUT_MS = 1_000
    private val requiredTools = ["kind", "docker", "kubectl"]

    fun check(env: Map<String, String> = System.getenv()): PreflightReport {
        requireTools()
        val credentials = requireCredentials(env)
        requireListening(MODEL_PROXY_PORT, "the model proxy")
        val warnings = buildList {
            if (!isListening(SEARXNG_PORT)) {
                add("searxng is not listening on 127.0.0.1:$SEARXNG_PORT; web search inside the review agent will fail")
            }
        }
        return PreflightReport(credentials = credentials, serverPort = freePort(), warnings = warnings)
    }

    fun requireTools() {
        val missing = requiredTools.filterNot(Shell::isOnPath)
        if (missing.isNotEmpty()) {
            throw E2ePreconditionException(
                "Missing required tool(s) on PATH: ${missing.joinToString()}. " +
                    "Install Docker Desktop, kind and kubectl before running the e2e suite.",
            )
        }
    }

    fun requireCredentials(env: Map<String, String>): Credentials =
        Credentials(openAiApiKey = env.required(OPENAI_API_KEY), modelProxyToken = env.required(MODEL_PROXY_TOKEN))

    fun requireListening(port: Int, description: String) {
        if (!isListening(port)) {
            throw E2ePreconditionException(
                "Nothing is listening on 127.0.0.1:$port ($description). Start it before running the e2e suite.",
            )
        }
    }

    fun isListening(port: Int, host: String = "127.0.0.1"): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
            true
        } catch (_: IOException) {
            false
        }

    /** Asks the OS for an ephemeral loopback port that the server child process can bind. */
    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun Map<String, String>.required(name: String): String =
        this[name]?.takeIf { it.isNotBlank() }
            ?: throw E2ePreconditionException("Set $name in the environment before running the e2e suite.")
}
