package org.sift.e2e

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A long-running `./kotlin run --module <module>` child JVM (operator or server) managed by the
 * harness. Output is teed to `build/e2e/<name>.log` and a bounded in-memory tail so failures can
 * quote the last lines without reading the file. [stop] tears down the whole process tree: the
 * wrapper script, the toolchain JVM and the application JVM it spawns.
 */
class HostProcess private constructor(
    val name: String,
    val logFile: File,
    private val process: Process,
) {
    private val tail = ArrayDeque<String>()
    private val watchers = ConcurrentHashMap<Regex, CompletableFuture<String>>()
    private val pump = Thread({ pumpOutput() }, "e2e-$name-log").apply { isDaemon = true }

    val isAlive: Boolean get() = process.isAlive

    /** Blocks until a line matching [pattern] is logged; fails fast if the process exits first. */
    fun awaitLogLine(pattern: Regex, timeout: Duration): String {
        val future = watchers.getOrPut(pattern) { CompletableFuture() }
        synchronized(tail) { tail.firstOrNull(pattern::containsMatchIn) }?.let(future::complete)
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            try {
                return future.get(POLL_INTERVAL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (!process.isAlive) throw exited("while waiting for /$pattern/")
            }
        }
        throw timedOut("no log line matched /$pattern/ within $timeout")
    }

    /** Blocks until `GET url` answers 200; fails fast if the process exits first. */
    fun awaitHttp(url: String, timeout: Duration) {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT.toJavaDuration()).GET().build()
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) throw exited("while waiting for $url")
            if (httpOk(request)) return
            Thread.sleep(POLL_INTERVAL.inWholeMilliseconds)
        }
        throw timedOut("$url did not answer 200 within $timeout")
    }

    /** Terminates the process and all of its descendants, escalating to SIGKILL after [GRACE_PERIOD]. */
    fun stop() {
        val handles = (process.descendants().toList() + process.toHandle()).distinctBy { it.pid() }
        handles.forEach { it.destroy() }
        if (!process.waitFor(GRACE_PERIOD.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            handles.forEach { it.destroyForcibly() }
            process.waitFor(GRACE_PERIOD.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
        handles.forEach(::awaitExit)
        pump.join(GRACE_PERIOD.inWholeMilliseconds)
        watchers.values.forEach { it.completeExceptionally(IllegalStateException("$name stopped")) }
    }

    /** Waits briefly for a killed descendant so no orphan `./kotlin run` JVM survives the harness. */
    private fun awaitExit(handle: ProcessHandle) {
        try {
            handle.onExit().get(GRACE_PERIOD.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            System.err.println("$name: descendant pid ${handle.pid()} is still alive after SIGKILL")
        }
    }

    /** Last [TAIL_LINES] lines of output, for failure messages. */
    fun recentOutput(): String = synchronized(tail) { tail.joinToString("\n") }

    private fun pumpOutput() {
        PrintWriter(logFile.bufferedWriter(), true).use { writer ->
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    writer.println(line)
                    synchronized(tail) {
                        tail.addLast(line)
                        if (tail.size > TAIL_LINES) tail.removeFirst()
                    }
                    watchers.entries
                        .filter { entry -> entry.key.containsMatchIn(line) }
                        .forEach { entry -> entry.value.complete(line) }
                }
            }
        }
    }

    private fun httpOk(request: HttpRequest): Boolean =
        try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == HTTP_OK
        } catch (_: IOException) {
            false
        }

    private fun exited(context: String) =
        E2ePreconditionException(
            "$name exited with code ${process.exitValue()} $context; see $logFile\n${recentOutput()}",
        )

    private fun timedOut(detail: String) =
        E2ePreconditionException("$name: $detail; see $logFile\n${recentOutput()}")

    companion object {
        private const val TAIL_LINES = 60
        private const val HTTP_OK = 200
        private val POLL_INTERVAL = 1.seconds
        private val HTTP_TIMEOUT = 5.seconds
        private val GRACE_PERIOD = 20.seconds
        private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(2)).build()

        /** Variables whose exact name must never reach a child, mirroring `dev.py run`. */
        val scrubbedNames: Set<String> = setOf(
            Preflight.OPENAI_API_KEY,
            Preflight.MODEL_PROXY_TOKEN,
            "SIFT_REVIEW_AUTH_TOKEN",
            "KUBECONFIG",
            "SERVER_PORT",
            "SERVER_ADDRESS",
        )

        /** Prefixes of variables that would let host Spring/Kubernetes state leak into the scenario. */
        val scrubbedPrefixes: List<String> = ["SPRING_", "KUBERNETES_", "SIFT_SERVER_", "SIFT_OPERATOR_"]

        /**
         * Starts `./kotlin run --module [module]` from the repo root with [scrubbedEnvironment] of the
         * current process plus [env]; stdout and stderr are merged into `build/e2e/<name>.log`.
         */
        fun start(
            name: String,
            module: String,
            env: Map<String, String>,
            logDir: File = RepoRoot.e2eBuildDir,
        ): HostProcess {
            logDir.mkdirs()
            val logFile = logDir.resolve("$name.log")
            val command = [RepoRoot.dir.resolve("kotlin").path, "run", "--module", module]
            val builder = ProcessBuilder(command).directory(RepoRoot.dir).redirectErrorStream(true)
            builder.environment().apply {
                clear()
                putAll(scrubbedEnvironment(System.getenv(), env))
            }
            val process = builder.startOrThrow(name)
            println("Started $name (pid ${process.pid()}), logging to $logFile")
            return HostProcess(name, logFile, process).also { it.pump.start() }
        }

        private fun ProcessBuilder.startOrThrow(name: String): Process =
            try {
                start()
            } catch (e: IOException) {
                throw E2ePreconditionException("Cannot start $name via `${command().joinToString(" ")}`", e)
            }

        /** Drops every [scrubbedNames]/[scrubbedPrefixes] entry from [base], then layers [overrides] on top. */
        fun scrubbedEnvironment(base: Map<String, String>, overrides: Map<String, String>): Map<String, String> =
            base.filterKeys { key -> key !in scrubbedNames && scrubbedPrefixes.none(key::startsWith) } + overrides
    }
}
