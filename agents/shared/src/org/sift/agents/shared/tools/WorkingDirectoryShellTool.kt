package org.sift.agents.shared.tools

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Port of the spring-ai-agent-utils `ShellTools` (`Bash`, `BashOutput`, `KillShell`) that runs
 * every command in a fixed [workingDirectory] instead of the process working directory.
 * Tool names, argument names, and output format match the upstream implementation so the
 * [org.sift.agents.shared.advisors.ToolCallAllowlist] applies unchanged.
 */
@Suppress("TooManyFunctions")
class WorkingDirectoryShellTool(private val workingDirectory: Path) {

    private val backgroundProcesses = ConcurrentHashMap<String, BackgroundProcess>()

    init {
        require(workingDirectory.isAbsolute) { "Shell working directory must be absolute" }
    }

    private class BackgroundProcess(private val process: Process) {
        private val stdout = StringBuilder()
        private val stderr = StringBuilder()
        private var lastStdoutPosition = 0
        private var lastStderrPosition = 0

        init {
            readLinesInBackground(process.inputStream.bufferedReader()) { line ->
                synchronized(stdout) { stdout.append(line).append('\n') }
            }
            readLinesInBackground(process.errorStream.bufferedReader()) { line ->
                synchronized(stderr) { stderr.append(line).append('\n') }
            }
        }

        fun getNewOutput(filter: String?): String {
            val pattern = filter?.takeIf { it.isNotEmpty() }?.let(::Regex)
            val result = StringBuilder()
            synchronized(stdout) {
                val newStdout = filterOutput(stdout.substring(lastStdoutPosition), pattern)
                if (newStdout.isNotEmpty()) {
                    result.append("STDOUT:\n").append(newStdout)
                }
                lastStdoutPosition = stdout.length
            }
            synchronized(stderr) {
                val newStderr = filterOutput(stderr.substring(lastStderrPosition), pattern)
                if (newStderr.isNotEmpty()) {
                    if (result.isNotEmpty()) {
                        result.append('\n')
                    }
                    result.append("STDERR:\n").append(newStderr)
                }
                lastStderrPosition = stderr.length
            }
            return result.toString()
        }

        private fun filterOutput(output: String, pattern: Regex?): String =
            if (pattern == null) {
                output
            } else {
                output.split('\n').filter { pattern.containsMatchIn(it) }.joinToString("") { "$it\n" }
            }

        fun isAlive(): Boolean = process.isAlive

        fun destroy() {
            process.destroy()
            try {
                if (!process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                process.destroyForcibly()
            }
        }

        fun getExitCode(): Int = process.exitValue()
    }

    @Tool(
        name = "Bash",
        description = """
        Execute a bash command for terminal operations like npm, docker, make, mvn, python.
        The command runs inside the working directory this tool is scoped to (the checked out repository).
        DO NOT use for file operations — use specialized tools instead:
        - File search: Use Glob (NOT find or ls)
        - Content search: Use Grep (NOT grep or rg)
        - Read files: Use Read (NOT cat/head/tail)
        - Edit files: Use Edit (NOT sed/awk)
        - Write files: Use Write (NOT echo >/cat <<EOF)

        Usage notes:
        - The command argument is required.
        - Optional timeout in milliseconds (max 600000ms / 10 minutes). Default: 120000ms (2 minutes).
        - Output truncated at 30000 characters.
        - Use run_in_background for long-running commands.
        - Quote file paths with spaces in double quotes.
        - Chain dependent commands with &&. Use ; if earlier failures are acceptable.
        - Paths are resolved relative to the working directory; do not cd elsewhere.

        Important notes:
        - NEVER run additional commands to read or explore code, besides git bash commands
        - NEVER use the TodoWrite or Task tools
        - IMPORTANT: Never use git commands with the -i flag (like git rebase -i or git add -i) since they require interactive input which is not supported.
        """,
    )
    @Suppress("ReturnCount", "UnreachableCatchBlock") // detekt misresolves IOException/InterruptedException
    fun bash(
        @ToolParam(description = "The command to execute") command: String,
        @ToolParam(description = "Optional timeout in milliseconds (max 600000)", required = false)
        timeout: Long?,
        @ToolParam(
            description = "Clear, concise description of what this command does in 5-10 words, in active voice. " +
                "Examples:\nInput: ls\nOutput: List files in current directory\n\nInput: git status\n" +
                "Output: Show working tree status\n\nInput: npm install\nOutput: Install package dependencies\n\n" +
                "Input: mkdir foo\nOutput: Create directory 'foo'",
            required = false,
        )
        @Suppress("UNUSED_PARAMETER") description: String?,
        @ToolParam(
            description = "Set to true to run this command in the background. Use BashOutput to read the output later.",
            required = false,
        )
        runInBackground: Boolean?,
    ): String {
        val shellId = "shell_" + System.currentTimeMillis()
        try {
            val process = ProcessBuilder(shellCommand(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(false)
                .start()

            if (runInBackground == true) {
                backgroundProcesses[shellId] = BackgroundProcess(process)
                return "bash_id: $shellId\n\nBackground shell started with ID: $shellId\n" +
                    "Use BashOutput tool with bash_id='$shellId' to retrieve output."
            }

            val timeoutMs = timeout?.let { minOf(it, MAX_TIMEOUT_MS) } ?: DEFAULT_TIMEOUT_MS
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = readLinesInBackground(process.inputStream.bufferedReader()) {
                stdout.append(it).append('\n')
            }
            val stderrThread = readLinesInBackground(process.errorStream.bufferedReader()) {
                stderr.append(it).append('\n')
            }

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                return "bash_id: $shellId\n\nCommand timed out after ${timeoutMs}ms"
            }
            stdoutThread.join(READER_JOIN_MS)
            stderrThread.join(READER_JOIN_MS)

            return formatResult(shellId, stdout, stderr, process.exitValue())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return "Command execution interrupted: ${e.message}"
        } catch (e: IOException) {
            return "Error executing command: ${e.message}"
        }
    }

    @Tool(
        name = "BashOutput",
        description = """
        - Retrieves output from a running or completed background bash shell
        - Takes a shell_id parameter identifying the shell
        - Always returns only new output since the last check
        - Returns stdout and stderr output along with shell status
        - Supports optional regex filtering to show only lines matching a pattern
        - Use this tool when you need to monitor or check the output of a long-running shell
        - Shell IDs can be found using the /bashes command
        """,
    )
    fun bashOutput(
        @ToolParam(description = "The ID of the background shell to retrieve output from")
        @Suppress("FunctionParameterNaming") bash_id: String,
        @ToolParam(
            description = "Optional regular expression to filter the output lines. Only lines matching this regex " +
                "will be included in the result. Any lines that do not match will no longer be available to read.",
            required = false,
        )
        filter: String?,
    ): String {
        val bgProcess = backgroundProcesses[bash_id]
        if (bgProcess == null) {
            return "Error: No background shell found with ID: $bash_id"
        }
        val newOutput = bgProcess.getNewOutput(filter)
        return buildString {
            append("Shell ID: ").append(bash_id).append('\n')
            append("Status: ").append(if (bgProcess.isAlive()) "Running" else "Completed").append('\n')
            if (!bgProcess.isAlive()) {
                try {
                    append("Exit code: ").append(bgProcess.getExitCode()).append('\n')
                } catch (_: IllegalThreadStateException) {
                    // Process not yet terminated
                }
            }
            if (newOutput.isNotEmpty()) {
                append("\nNew output:\n").append(newOutput)
            } else {
                append("\nNo new output since last check.")
            }
        }
    }

    @Tool(
        name = "KillShell",
        description = """
        - Kills a running background bash shell by its ID
        - Takes a shell_id parameter identifying the shell to kill
        - Returns a success or failure status
        - Use this tool when you need to terminate a long-running shell
        - Shell IDs can be found using the /bashes command
        """,
    )
    fun killShell(
        @ToolParam(description = "The ID of the background shell to kill")
        @Suppress("FunctionParameterNaming") bash_id: String,
    ): String {
        val bgProcess = backgroundProcesses[bash_id]
        val message = when {
            bgProcess == null -> "Error: No background shell found with ID: $bash_id"
            !bgProcess.isAlive() -> "Shell $bash_id was already terminated. Removed from active shells."
            else -> {
                bgProcess.destroy()
                try {
                    Thread.sleep(KILL_CONFIRM_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                "Successfully killed shell: $bash_id"
            }
        }
        if (bgProcess != null) {
            backgroundProcesses.remove(bash_id)
        }
        return message
    }

    private fun shellCommand(command: String): List<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd.exe", "/c", command)
        } else {
            listOf("/bin/bash", "-c", command)
        }

    private fun formatResult(shellId: String, stdout: StringBuilder, stderr: StringBuilder, exitCode: Int): String {
        val header = "bash_id: $shellId\n\n"
        val body = StringBuilder()
        if (stdout.isNotEmpty()) {
            body.append(stdout)
        }
        if (stderr.isNotEmpty()) {
            if (body.isNotEmpty()) {
                body.append('\n')
            }
            body.append("STDERR:\n").append(stderr)
        }
        if (exitCode != 0) {
            if (body.isNotEmpty()) {
                body.append('\n')
            }
            body.append("Exit code: ").append(exitCode)
        }
        val output = header + body
        if (output.length <= MAX_OUTPUT_CHARS) {
            return output
        }
        return header + body.substring(0, minOf(body.length, MAX_OUTPUT_CHARS - header.length)) +
            "\n... (output truncated)"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 120_000L
        const val MAX_TIMEOUT_MS: Long = 600_000L
        const val MAX_OUTPUT_CHARS: Int = 30_000
        private const val KILL_GRACE_SECONDS = 5L
        private const val READER_JOIN_MS = 1_000L
        private const val KILL_CONFIRM_MS = 500L

        private fun readLinesInBackground(reader: BufferedReader, onLine: (String) -> Unit): Thread {
            val thread = Thread {
                try {
                    reader.use { stream ->
                        var line = stream.readLine()
                        while (line != null) {
                            onLine(line)
                            line = stream.readLine()
                        }
                    }
                } catch (_: IOException) {
                    // Process terminated or stream closed
                }
            }
            thread.isDaemon = true
            thread.start()
            return thread
        }
    }
}
