package org.sift.agents.review

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCheckoutServiceTest {

    private val service = GitCheckoutService()
    private val cleanupPaths = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        cleanupPaths.forEach { it.deleteRecursively() }
    }

    @Test
    fun `checkout clones the repository and computes the merge base diff`() {
        val origin = createOriginRepository()

        val checkout = service.checkout(reviewProperties(repositoryUrl = "file://$origin", commitSha = featureSha(origin)))
        cleanupPaths.add(checkout.dir)

        assertTrue(checkout.dir.exists())
        assertTrue("+feature change" in checkout.diff)
        assertFalse("main only change" in checkout.diff)
        assertEquals(featureSha(origin), git(checkout.dir, "rev-parse", "HEAD").trim())
    }

    @Test
    fun `checkout fails when the branch tip does not match the requested commit sha`() {
        val origin = createOriginRepository()
        val mainSha = git(origin, "rev-parse", "main").trim()
        val before = tempCheckoutDirectories()

        val exception = assertFailsWith<CommitShaMismatchException> {
            service.checkout(reviewProperties(repositoryUrl = "file://$origin", commitSha = mainSha))
        }

        assertTrue(mainSha in exception.message.orEmpty())
        assertTrue(featureSha(origin) in exception.message.orEmpty())
        assertEquals(before, tempCheckoutDirectories())
    }

    @Test
    fun `cleanup deletes the checkout directory`() {
        val origin = createOriginRepository()

        val checkout = service.checkout(reviewProperties(repositoryUrl = "file://$origin", commitSha = featureSha(origin)))
        cleanupPaths.add(checkout.dir)
        assertTrue(checkout.dir.exists())

        checkout.cleanup()

        assertFalse(checkout.dir.exists())
    }

    @Test
    fun `checkout of an invalid repository throws and leaves no temp directory behind`() {
        val properties = reviewProperties(repositoryUrl = "file:///nonexistent/sift-missing-repo")
        val before = tempCheckoutDirectories()

        val exception = assertFailsWith<GitCommandException> {
            service.checkout(properties)
        }

        val message = exception.message.orEmpty()
        assertTrue("git command" in message)
        assertTrue("clone" in message)
        assertEquals(before, tempCheckoutDirectories())
    }

    private fun createOriginRepository(): Path {
        val origin = Files.createTempDirectory("sift-review-origin")
        cleanupPaths.add(origin)
        git(origin, "init", "-b", "main")
        origin.resolve("README.md").writeText("hello\n")
        git(origin, "add", ".")
        git(origin, "commit", "-m", "initial commit")
        git(origin, "checkout", "-b", "feature")
        origin.resolve("README.md").writeText("hello\nfeature change\n")
        git(origin, "add", ".")
        git(origin, "commit", "-m", "feature commit")
        git(origin, "checkout", "main")
        origin.resolve("main.txt").writeText("main only change\n")
        git(origin, "add", ".")
        git(origin, "commit", "-m", "main only commit")
        return origin
    }

    private fun featureSha(origin: Path): String = git(origin, "rev-parse", "feature").trim()

    private fun reviewProperties(
        repositoryUrl: String,
        commitSha: String = "0123456789abcdef0123456789abcdef01234567",
    ) = ReviewProperties(
        repositoryUrl = repositoryUrl,
        branch = "feature",
        baseBranch = "main",
        commitSha = commitSha,
        executionId = "review-uid:1",
        pullRequest = null,
        authToken = null,
    )

    private fun git(dir: Path, vararg args: String): String {
        val command = listOf("git", "-c", "user.name=Sift Test", "-c", "user.email=sift@test.local") + args
        val process = ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0) {
            "git ${args.joinToString(" ")} failed: $output"
        }
        return output
    }

    private fun tempCheckoutDirectories(): Set<String> =
        Path(System.getProperty("java.io.tmpdir"))
            .listDirectoryEntries("sift-review*")
            .map { it.name }
            .toSet()

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
    }
}
