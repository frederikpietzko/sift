package org.sift.agents.shared.tools

import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.support.ToolCallbacks
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingDirectoryShellToolTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `commands run in the configured working directory`() {
        val output = WorkingDirectoryShellTool(directory).bash("pwd", null, null, null)

        assertTrue(output.startsWith("bash_id: shell_"), output)
        assertTrue(directory.toRealPath().toString() in output, output)
        assertFalse("Exit code" in output, output)
    }

    @Test
    fun `stderr and non-zero exit codes are reported`() {
        val output = WorkingDirectoryShellTool(directory).bash("ls does-not-exist", null, null, null)

        assertTrue("STDERR:\n" in output, output)
        assertTrue("does-not-exist" in output, output)
        assertTrue(output.endsWith("Exit code: 2") || output.endsWith("Exit code: 1"), output)
    }

    @Test
    fun `output is truncated and timeouts terminate the command`() {
        val tool = WorkingDirectoryShellTool(directory)

        val long = tool.bash("yes | head -c 100000", null, null, null)
        assertTrue(long.endsWith("\n... (output truncated)"), long)
        assertTrue(long.length < 100_000)

        val timedOut = tool.bash("sleep 30", 200, null, null)
        assertTrue("Command timed out after 200ms" in timedOut, timedOut)
    }

    @Test
    fun `background shells report output and can be killed`() {
        val tool = WorkingDirectoryShellTool(directory)

        val started = tool.bash("echo started; sleep 30", null, null, true)
        val bashId = requireNotNull(Regex("bash_id: (shell_\\d+)").find(started)?.groupValues?.get(1)) { started }

        Thread.sleep(500)
        val output = tool.bashOutput(bashId, null)
        assertTrue("Status: Running" in output, output)
        assertTrue("STDOUT:\nstarted" in output, output)
        assertTrue("No new output since last check." in tool.bashOutput(bashId, null))

        assertEquals("Successfully killed shell: $bashId", tool.killShell(bashId))
        assertTrue(tool.bashOutput(bashId, null).startsWith("Error: No background shell found"))
    }

    @Test
    fun `tools are exposed with the upstream names and argument names`() {
        val callbacks = ToolCallbacks.from(WorkingDirectoryShellTool(directory))

        assertEquals(setOf("Bash", "BashOutput", "KillShell"), callbacks.map { it.toolDefinition.name() }.toSet())
        val schema = callbacks.single { it.toolDefinition.name() == "Bash" }.toolDefinition.inputSchema()
        listOf("\"command\"", "\"timeout\"", "\"description\"", "\"runInBackground\"")
            .forEach { assertTrue(it in schema, schema) }
    }

    @Test
    fun `relative working directories are rejected`() {
        assertFailsWith<IllegalArgumentException> { WorkingDirectoryShellTool(Path.of("relative")) }
    }
}
