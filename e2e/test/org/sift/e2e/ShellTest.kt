package org.sift.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ShellTest {

    @Test
    fun `run captures stdout and exit code`() {
        val result = Shell.run("sh", "-c", "echo out; echo err 1>&2; exit 3", timeout = 10.seconds)
        assertEquals(3, result.exitCode)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
    }

    @Test
    fun `runOrThrow reports the stderr tail on failure`() {
        val error = assertFailsWith<E2ePreconditionException> {
            Shell.runOrThrow("sh", "-c", "echo boom 1>&2; exit 1", timeout = 10.seconds)
        }
        assertTrue("boom" in error.message.orEmpty())
        assertTrue("exit code 1" in error.message.orEmpty())
    }

    @Test
    fun `run fails fast for an unknown executable`() {
        assertFailsWith<E2ePreconditionException> { Shell.run("definitely-not-a-tool-sift-e2e") }
    }

    @Test
    fun `isOnPath finds sh and rejects unknown tools`() {
        assertTrue(Shell.isOnPath("sh"))
        assertTrue(!Shell.isOnPath("definitely-not-a-tool-sift-e2e"))
    }
}
