package org.sift.agents.review

import org.springframework.stereotype.Service
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

data class Checkout(val dir: Path, val diff: String) {
    fun cleanup() {
        dir.deleteRecursively()
    }
}

class GitCommandException(command: List<String>, detail: String) :
    RuntimeException("git command '${redactCredentials(command.joinToString(" "))}' $detail")

class CommitShaMismatchException(branch: String, expected: String, actual: String) :
    RuntimeException("branch '$branch' resolved to commit $actual but commit $expected was requested")

@Service
class GitCheckoutService {

    fun checkout(properties: ReviewProperties): Checkout {
        val dir = Files.createTempDirectory(TEMP_DIRECTORY_PREFIX)
        var success = false
        try {
            runGit(dir, "clone", cloneUrl(properties), ".")
            runGit(dir, "fetch", "origin", properties.baseBranch, properties.branch)
            val expected = properties.commitSha.lowercase()
            val actual = runGit(dir, "rev-parse", "--verify", "origin/${properties.branch}^{commit}").trim()
            if (actual != expected) {
                throw CommitShaMismatchException(properties.branch, expected, actual)
            }
            runGit(dir, "checkout", "--detach", expected)
            runGit(dir, "branch", "--force", properties.branch, expected)
            runGit(dir, "branch", "--force", properties.baseBranch, "origin/${properties.baseBranch}")
            val diff = runGit(dir, "diff", "${properties.baseBranch}...${properties.branch}")
            success = true
            return Checkout(dir = dir, diff = diff)
        } finally {
            if (!success) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cloneUrl(properties: ReviewProperties): String {
        val token = properties.authToken?.takeIf { it.isNotBlank() }
        val uri = URI.create(properties.repositoryUrl)
        return if (token == null || !uri.scheme.equals("https", ignoreCase = true)) {
            properties.repositoryUrl
        } else {
            val port = if (uri.port == -1) "" else ":${uri.port}"
            "https://$token@${uri.host}$port${uri.rawPath}"
        }
    }

    private fun runGit(workingDir: Path, vararg args: String): String {
        val command = listOf("git") + args
        val stdoutFile = Files.createTempFile("sift-git-stdout", null)
        val stderrFile = Files.createTempFile("sift-git-stderr", null)
        try {
            val process = ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile())
                .start()
            val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw GitCommandException(command, "timed out after $GIT_TIMEOUT_SECONDS seconds")
            }
            if (process.exitValue() != 0) {
                throw GitCommandException(
                    command,
                    "exited with code ${process.exitValue()}: ${stderrFile.readText().trim()}",
                )
            }
            return stdoutFile.readText()
        } finally {
            stdoutFile.deleteIfExists()
            stderrFile.deleteIfExists()
        }
    }

    private companion object {
        const val TEMP_DIRECTORY_PREFIX = "sift-review"
        const val GIT_TIMEOUT_SECONDS = 300L
    }
}

private val credentialsRegex = Regex("://[^/@\\s]+@")

private fun redactCredentials(value: String): String = value.replace(credentialsRegex, "://***@")
