package org.sift.e2e

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Captured outcome of a finished external command. */
data class ShellResult(val command: List<String>, val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0

    /** Last [lines] of stderr (falls back to stdout), for compact failure messages. */
    fun errorTail(lines: Int = ERROR_TAIL_LINES): String =
        stderr.ifBlank { stdout }.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines).joinToString("\n")

    private companion object {
        const val ERROR_TAIL_LINES = 20
    }
}

/**
 * Thin, synchronous `ProcessBuilder` wrapper for short-lived CLI calls (`kind`, `docker`, ...).
 * Extra environment entries are passed to the child but never echoed into messages or logs.
 */
object Shell {
    private val defaultTimeout = 2.minutes

    /**
     * Runs [cmd] in [workingDir], waiting at most [timeout]. Returns the result regardless of the
     * exit code; use [runOrThrow] for the fail-fast variant.
     */
    fun run(
        vararg cmd: String,
        workingDir: File = RepoRoot.dir,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = defaultTimeout,
    ): ShellResult {
        val command = cmd.toList()
        val process = start(command, workingDir, env)
        // Drain both streams concurrently so a chatty child cannot block on a full pipe.
        val stdout = process.inputStream.readAllAsync()
        val stderr = process.errorStream.readAllAsync()
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw E2ePreconditionException("`${command.joinToString(" ")}` did not finish within $timeout")
        }
        return ShellResult(command, process.exitValue(), stdout.get(), stderr.get())
    }

    /** Like [run] but throws [E2ePreconditionException] with a stderr tail on a non-zero exit code. */
    fun runOrThrow(
        vararg cmd: String,
        workingDir: File = RepoRoot.dir,
        env: Map<String, String> = emptyMap(),
        timeout: Duration = defaultTimeout,
    ): ShellResult {
        val result = run(*cmd, workingDir = workingDir, env = env, timeout = timeout)
        if (!result.succeeded) {
            val rendered = result.command.joinToString(" ")
            val detail = "failed with exit code ${result.exitCode}:\n${result.errorTail()}"
            throw E2ePreconditionException("`$rendered` $detail")
        }
        return result
    }

    /** True when an executable named [tool] exists in one of the `PATH` directories. */
    fun isOnPath(tool: String): Boolean =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { dir -> dir.isNotBlank() && File(dir, tool).let { it.isFile && it.canExecute() } }

    private fun start(command: List<String>, workingDir: File, env: Map<String, String>): Process =
        try {
            ProcessBuilder(command).directory(workingDir).apply { environment().putAll(env) }.start()
        } catch (e: IOException) {
            throw E2ePreconditionException("Cannot start `${command.first()}`: is it installed and on PATH?", e)
        }

    private fun InputStream.readAllAsync(): Future<String> =
        CompletableFuture.supplyAsync { bufferedReader().use { it.readText() } }
}
