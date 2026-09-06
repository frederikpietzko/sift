package org.sift.agents.review

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.sift.agents.shared.advisors.ToolAllowlistAdvisor
import org.sift.agents.shared.tools.SearxngSearchTool
import org.sift.events.Severity
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.ai.chat.client.advisor.api.Advisor
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewAgentTest {

    private val chatClientBuilder = mockk<ChatClient.Builder>()
    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val webSearchTool = mockk<ObjectProvider<SearxngSearchTool>>(relaxed = true)

    private val userMessages = mutableListOf<String>()

    private val fixedInstant = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private val checkoutDir: Path = Files.createTempDirectory("sift-review-agent-test")

    private val agent by lazy {
        every { chatClientBuilder.defaultAdvisors(*anyVararg<Advisor>()) } returns chatClientBuilder
        every { chatClientBuilder.build() } returns chatClient
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(capture(userMessages)) } returns requestSpec
        every { requestSpec.tools(*anyVararg<Any>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
        ReviewAgent(chatClientBuilder, webSearchTool, clock)
    }

    @AfterTest
    fun tearDown() {
        checkoutDir.deleteRecursively()
    }

    @Test
    fun `review sends the diff to the model and returns the structured result`() {
        val expected = reviewResult()
        every { callSpec.entity(ReviewResult::class.java) } returns expected
        val diff = "diff --git a/File.kt b/File.kt\n+added line"

        val result = agent.review(Checkout(dir = checkoutDir, diff = diff))

        assertEquals(expected, result)
        val userMessage = capturedUserMessage()
        assertTrue(diff in userMessage)
        assertTrue(checkoutDir.toString() in userMessage)
        assertFalse(ReviewAgent.TRUNCATION_NOTE in userMessage)
    }

    @Test
    fun `review client includes a logging advisor`() {
        every { callSpec.entity(ReviewResult::class.java) } returns reviewResult()

        agent.review(Checkout(dir = checkoutDir, diff = "diff"))

        verify(exactly = 1) {
            chatClientBuilder.defaultAdvisors(any<SimpleLoggerAdvisor>(), any<ToolAllowlistAdvisor>())
        }
    }

    @Test
    fun `review caps an oversized diff and appends a truncation note`() {
        every { callSpec.entity(ReviewResult::class.java) } returns reviewResult()
        val diff = "x".repeat(ReviewAgent.MAX_DIFF_CHARS + 1_000)

        agent.review(Checkout(dir = checkoutDir, diff = diff))

        val userMessage = capturedUserMessage()
        assertFalse(diff in userMessage)
        assertTrue(diff.take(ReviewAgent.MAX_DIFF_CHARS) in userMessage)
        assertTrue(ReviewAgent.TRUNCATION_NOTE in userMessage)
    }

    @Test
    fun `review retries once when the structured output is malformed`() {
        val expected = reviewResult()
        every { callSpec.entity(ReviewResult::class.java) }
            .throws(IllegalStateException("malformed output"))
            .andThen(expected)

        val result = agent.review(Checkout(dir = checkoutDir, diff = "diff"))

        assertEquals(expected, result)
        verify(exactly = 2) { callSpec.entity(ReviewResult::class.java) }
    }

    @Test
    fun `review rethrows when the retry also fails`() {
        every { callSpec.entity(ReviewResult::class.java) }
            .throws(IllegalStateException("first"))
            .andThenThrows(IllegalStateException("second"))

        val exception = assertFailsWith<IllegalStateException> {
            agent.review(Checkout(dir = checkoutDir, diff = "diff"))
        }

        assertEquals("second", exception.message)
        verify(exactly = 2) { callSpec.entity(ReviewResult::class.java) }
    }

    @Test
    fun `review of an empty diff still calls the model and maps empty findings`() {
        val expected = ReviewResult(summary = "Nothing to review.", findings = emptyList())
        every { callSpec.entity(ReviewResult::class.java) } returns expected

        val result = agent.review(Checkout(dir = checkoutDir, diff = ""))

        assertEquals(expected, result)
        verify(exactly = 1) { callSpec.entity(ReviewResult::class.java) }

        val event = agent.toEvent(reviewProperties(), result)
        assertEquals(emptyList(), event.findings)
    }

    @Test
    fun `toEvent maps all fields of the result and properties`() {
        val properties = reviewProperties()
        val result = reviewResult()

        val event = agent.toEvent(properties, result)

        assertEquals(properties.repositoryUrl, event.repositoryUrl)
        assertEquals(properties.branch, event.branch)
        assertEquals(properties.baseBranch, event.baseBranch)
        assertEquals(properties.commitSha, event.commitSha)
        assertEquals(properties.executionId, event.executionId)
        assertEquals(properties.pullRequest, event.pullRequest)
        assertEquals(result.summary, event.summary)
        assertEquals(fixedInstant, event.completedAt)
        assertEquals(1, event.findings.size)
        val finding = event.findings.single()
        val reviewFinding = result.findings.single()
        assertEquals(reviewFinding.file, finding.file)
        assertEquals(reviewFinding.startLine, finding.startLine)
        assertEquals(reviewFinding.endLine, finding.endLine)
        assertEquals(reviewFinding.severity, finding.severity)
        assertEquals(reviewFinding.category, finding.category)
        assertEquals(reviewFinding.message, finding.message)
        assertEquals(reviewFinding.suggestion, finding.suggestion)
    }

    private fun capturedUserMessage(): String {
        verify(exactly = 1) { requestSpec.user(any<String>()) }
        return userMessages.single()
    }

    private fun reviewResult() = ReviewResult(
        summary = "Looks mostly fine.",
        findings = listOf(
            ReviewFinding(
                file = "src/Main.kt",
                startLine = 10,
                endLine = 12,
                severity = Severity.MAJOR,
                category = "bug",
                message = "Possible null pointer dereference.",
                suggestion = "Use a safe call operator.",
            ),
        ),
    )

    private fun reviewProperties() = ReviewProperties(
        repositoryUrl = "https://example.com/org/repo.git",
        branch = "feature",
        baseBranch = "main",
        commitSha = "0123456789abcdef0123456789abcdef01234567",
        executionId = "review-uid:1",
        pullRequest = "42",
        authToken = null,
    )
}
