package org.sift.agents.review

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.sift.agents.shared.messaging.EventPublisher
import org.sift.events.CodeReviewCompletedEvent
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewRunnerTest {

    private val properties = ReviewProperties(
        repositoryUrl = "https://example.com/org/repo.git",
        branch = "feature",
        baseBranch = "main",
        pullRequest = "42",
        authToken = null,
    )

    private val gitCheckoutService = mockk<GitCheckoutService>()
    private val reviewAgent = mockk<ReviewAgent>()
    private val eventPublisher = mockk<EventPublisher>()

    private val runner = ReviewRunner(properties, gitCheckoutService, reviewAgent, eventPublisher)

    private val checkoutDir: Path = Files.createTempDirectory("sift-review-runner-test")
    private val checkout = Checkout(dir = checkoutDir, diff = "diff")

    private val result = ReviewResult(summary = "Looks fine.", findings = emptyList())
    private val event = CodeReviewCompletedEvent(
        repositoryUrl = properties.repositoryUrl,
        branch = properties.branch,
        baseBranch = properties.baseBranch,
        pullRequest = properties.pullRequest,
        summary = result.summary,
        findings = emptyList(),
        completedAt = Instant.parse("2026-09-05T00:00:00Z"),
    )

    @AfterTest
    fun tearDown() {
        checkoutDir.deleteRecursively()
    }

    @Test
    fun `runs checkout, review, event mapping, publish, and cleanup in order`() {
        every { gitCheckoutService.checkout(properties) } returns checkout
        every { reviewAgent.review(checkout) } returns result
        every { reviewAgent.toEvent(properties, result) } returns event
        every { eventPublisher.publish(event) } returns Unit

        runner.run(DefaultApplicationArguments())

        verifyOrder {
            gitCheckoutService.checkout(properties)
            reviewAgent.review(checkout)
            reviewAgent.toEvent(properties, result)
            eventPublisher.publish(event)
        }
        assertFalse(checkoutDir.exists())
    }

    @Test
    fun `propagates a publish failure and still cleans up the checkout`() {
        every { gitCheckoutService.checkout(properties) } returns checkout
        every { reviewAgent.review(checkout) } returns result
        every { reviewAgent.toEvent(properties, result) } returns event
        every { eventPublisher.publish(event) } throws IllegalStateException("broker down")

        assertFailsWith<IllegalStateException> {
            runner.run(DefaultApplicationArguments())
        }

        assertFalse(checkoutDir.exists())
    }

    @Test
    fun `propagates a review failure and still cleans up the checkout`() {
        every { gitCheckoutService.checkout(properties) } returns checkout
        every { reviewAgent.review(checkout) } throws IllegalStateException("model failure")

        assertFailsWith<IllegalStateException> {
            runner.run(DefaultApplicationArguments())
        }

        verify(exactly = 0) { eventPublisher.publish(any()) }
        assertFalse(checkoutDir.exists())
    }

    @Test
    fun `does not review or publish when the checkout fails`() {
        every { gitCheckoutService.checkout(properties) } throws
            GitCommandException(listOf("git", "clone"), "exited with code 128")

        assertFailsWith<GitCommandException> {
            runner.run(DefaultApplicationArguments())
        }

        verify(exactly = 0) { reviewAgent.review(any()) }
        verify(exactly = 0) { eventPublisher.publish(any()) }
        assertTrue(checkoutDir.exists())
    }
}
