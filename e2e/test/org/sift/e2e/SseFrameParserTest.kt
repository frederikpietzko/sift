package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sift.e2e.SseFrameReader.Companion.payload
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseFrameParserTest {

    private fun reader(text: String) = SseFrameReader(StringReader(text).buffered())

    @Test
    fun `parses event id and data and skips heartbeat comments`() {
        val stream = """
            :heartbeat

            event:SNAPSHOT
            id: 1
            data: {"run":{"id":"r1","phase":"CREATED"}}

            :heartbeat

            :heartbeat

            event: UPDATED
            id:2
            data:{"run":{"id":"r1",
            data:"phase":"RUNNING"}}
        """.trimIndent() + "\n\n"
        val reader = reader(stream)

        val snapshot = reader.nextFrame()
        assertEquals(SseFrame("SNAPSHOT", "1", """{"run":{"id":"r1","phase":"CREATED"}}"""), snapshot)
        assertEquals("CREATED", snapshot.payload().path("phase").asString())

        val updated = reader.nextFrame()
        assertEquals("UPDATED", updated.event)
        assertEquals("2", updated.id)
        assertEquals("RUNNING", updated.payload().path("phase").asString())
    }

    @Test
    fun `fails once the stream ends`() {
        val reader = reader(":heartbeat\n\n")

        assertThrows<IllegalStateException> { reader.nextFrame() }
    }

    @Test
    fun `payload requires a run object`() {
        val frame = SseFrame("UPDATED", null, """{"other":1}""")

        assertThrows<IllegalStateException> { frame.payload() }
        assertNull(SseFrame("x", null, null).data)
    }

    @Test
    fun `observer records distinct phases until SUCCESS and tolerates repeats and non-UPDATED frames`() {
        val frames = sequenceOf(
            update("CREATED"),
            SseFrame("SNAPSHOT", null, run("CREATED")),
            update("PENDING"),
            update("PENDING"),
            update("RUNNING"),
            update("RUNNING"),
            update("SUCCESS", reason = "ResultReceived"),
            update("SUCCESS"),
        )

        val outcome = RunObserver.followUntilTerminal(frames)

        assertEquals(listOf("CREATED", "PENDING", "RUNNING", "SUCCESS"), outcome.phases)
        assertEquals("SUCCESS", outcome.finalPhase)
        assertEquals("ResultReceived", outcome.finalRun.path("reason").asString())
    }

    @Test
    fun `observer fails fast with reason and message on FAILED`() {
        val frames = sequenceOf(
            update("PENDING"),
            update("FAILED", reason = "ImagePullFailed", message = "manifest unknown"),
            update("SUCCESS"),
        )

        val failure = assertThrows<RunFailedException> { RunObserver.followUntilTerminal(frames) }

        assertEquals(
            "Run ended in FAILED (reason=ImagePullFailed, message=manifest unknown); phases observed: [PENDING, FAILED]",
            failure.message,
        )
    }

    @Test
    fun `observer fails when the stream ends before a terminal phase`() {
        assertThrows<IllegalStateException> { RunObserver.followUntilTerminal(sequenceOf(update("RUNNING"))) }
    }

    private fun run(phase: String, reason: String? = null, message: String? = null): String =
        """{"run":{"id":"r1","phase":"$phase","reason":${json(reason)},"message":${json(message)}}}"""

    private fun update(phase: String, reason: String? = null, message: String? = null): SseFrame =
        SseFrame("UPDATED", null, run(phase, reason, message))

    private fun json(value: String?): String = value?.let { "\"$it\"" } ?: "null"
}
