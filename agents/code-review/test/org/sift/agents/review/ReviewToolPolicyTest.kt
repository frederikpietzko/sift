package org.sift.agents.review

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.io.TempDir
import org.sift.agents.shared.tools.SearxngSearchTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewToolPolicyTest {
    @TempDir
    lateinit var checkoutDir: Path

    @Test
    fun `review defaults block actual filesystem writes and shell tools`() {
        val model = mockk<ChatModel>()
        val prompts = mutableListOf<Prompt>()
        val jsonMapper = JsonMapper.builder().build()
        val writtenFile = checkoutDir.resolve("forbidden.txt")
        val shellFile = checkoutDir.resolve("shell.txt")
        val calls = listOf(
            AssistantMessage.ToolCall(
                "1", "function", "Write",
                jsonMapper.writeValueAsString(mapOf("filePath" to writtenFile.toString(), "content" to "blocked")),
            ),
            AssistantMessage.ToolCall(
                "2", "function", "Bash",
                jsonMapper.writeValueAsString(mapOf("command" to "touch $shellFile")),
            ),
        )
        val rounds = listOf(
            ChatResponse(listOf(Generation(AssistantMessage.builder().toolCalls(calls).build()))),
            ChatResponse(listOf(Generation(AssistantMessage("""{"summary":"Reviewed","findings":[]}""")))),
        ).iterator()
        every { model.options } returns ToolCallingChatOptions.builder().build()
        every { model.call(any<Prompt>()) } answers {
            prompts.add(firstArg())
            rounds.next()
        }
        val agent = ReviewAgent(
            chatClientBuilder = ChatClient.builder(model),
            webSearchTool = mockk<ObjectProvider<SearxngSearchTool>>(relaxed = true),
        )

        assertEquals("Reviewed", agent.review(Checkout(dir = checkoutDir, diff = "diff")).summary)
        assertEquals("blocked", Files.readString(writtenFile))
        assertFalse(Files.exists(shellFile))
        assertEquals(2, prompts.size)
        val responses = prompts.last().instructions.filterIsInstance<ToolResponseMessage>().single().responses
        assertEquals(2, responses.size)
        assertFalse(responses[0].responseData().startsWith("Shell command denied by policy"))
        assertTrue(responses[1].responseData().startsWith("Shell command denied by policy"))
    }

    @Test
    fun `configuration binds permissions and injects them into the review agent`() {
        val model = mockk<ChatModel>()
        val prompts = mutableListOf<Prompt>()
        every { model.options } returns ToolCallingChatOptions.builder().build()
        every { model.call(any<Prompt>()) } answers {
            prompts.add(firstArg())
            ChatResponse(listOf(Generation(AssistantMessage("""{"summary":"Reviewed","findings":[]}"""))))
        }
        ApplicationContextRunner()
            .withUserConfiguration(PolicyConfiguration::class.java)
            .withBean(ChatClient.Builder::class.java, { ChatClient.builder(model) })
            .withPropertyValues(
                "sift.review.tools.allowed-shell-commands[0]=pwd",
            )
            .run { context ->
                assertNull(context.startupFailure)
                val properties = context.getBean(ReviewToolProperties::class.java)
                assertEquals(setOf("pwd"), properties.allowedShellCommands)
                context.getBean(ReviewAgent::class.java).review(Checkout(dir = checkoutDir, diff = "diff"))
                assertTrue(requireNotNull(prompts.single().userMessage.text).contains("Exact allowed shell commands: pwd"))
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewToolProperties::class)
    @Import(ReviewAgent::class)
    class PolicyConfiguration
}
