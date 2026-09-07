package org.sift.e2e

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreflightTest {

    @Test
    fun `requireCredentials names the missing variable without echoing values`() {
        val error = assertFailsWith<E2ePreconditionException> {
            Preflight.requireCredentials(mapOf(Preflight.OPENAI_API_KEY to "sk-secret"))
        }
        assertTrue(Preflight.MODEL_PROXY_TOKEN in error.message.orEmpty())
        assertFalse("sk-secret" in error.message.orEmpty())
    }

    @Test
    fun `requireCredentials rejects blank values`() {
        assertFailsWith<E2ePreconditionException> {
            Preflight.requireCredentials(mapOf(Preflight.OPENAI_API_KEY to " ", Preflight.MODEL_PROXY_TOKEN to "t"))
        }
    }

    @Test
    fun `requireCredentials returns both values when present`() {
        val credentials = Preflight.requireCredentials(
            mapOf(Preflight.OPENAI_API_KEY to "key", Preflight.MODEL_PROXY_TOKEN to "token"),
        )
        assertEquals(Credentials(openAiApiKey = "key", modelProxyToken = "token"), credentials)
    }

    @Test
    fun `isListening reflects an open loopback socket`() {
        ServerSocket(0).use { socket ->
            assertTrue(Preflight.isListening(socket.localPort))
        }
        val closedPort = Preflight.freePort()
        assertFalse(Preflight.isListening(closedPort))
    }
}
