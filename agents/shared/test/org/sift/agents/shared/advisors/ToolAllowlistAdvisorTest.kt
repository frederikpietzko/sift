package org.sift.agents.shared.advisors

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import reactor.core.publisher.Flux
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolAllowlistAdvisorTest {
    private val model = mockk<ChatModel>()
    private val prompts = mutableListOf<Prompt>()
    private val executions = mutableListOf<String>()
    private val contexts = mutableListOf<ToolContext?>()
    private val allowlist = ToolCallAllowlist(
        allowedShellCommands = setOf("pwd"),
    )

    @Test
    fun `real client executes only allowed calls across batches and subsequent rounds`() {
        val rounds = responses().iterator()
        every { model.options } returns ToolCallingChatOptions.builder().build()
        every { model.call(any<Prompt>()) } answers {
            prompts.add(firstArg())
            rounds.next()
        }

        val result = client().prompt().user("Review")
            .tools(callback("Bash"), callback("Write"), callback("Read"), callback("Edit"), callback("CustomTool"))
            .toolContext(mapOf("checkout" to "/repo"))
            .call().content()

        assertEquals("Review complete", result)
        assertExecutionsAndFeedback()
    }

    @Test
    fun `streaming uses the same guard and returns denial feedback to the model`() {
        val rounds = responses().iterator()
        every { model.options } returns ToolCallingChatOptions.builder().build()
        every { model.stream(any<Prompt>()) } answers {
            prompts.add(firstArg())
            Flux.just(rounds.next())
        }

        val result = client().prompt().user("Review")
            .tools(callback("Bash"), callback("Write"), callback("Read"), callback("Edit"), callback("CustomTool"))
            .toolContext(mapOf("checkout" to "/repo"))
            .stream().content().collectList().block(Duration.ofSeconds(10))

        assertTrue(requireNotNull(result).contains("Review complete"))
        assertExecutionsAndFeedback()
    }

    @Test
    fun `non-shell tools retain direct return behavior`() {
        every { model.options } returns ToolCallingChatOptions.builder().build()
        every { model.call(any<Prompt>()) } returns toolResponse(
            AssistantMessage.ToolCall("1", "function", "Write", "{}"),
        )

        val result = client().prompt().user("Review")
            .tools(callback("Write", returnDirect = true))
            .call().content()

        assertEquals("Allowed result", result)
        assertEquals(listOf("Write:{}"), executions)
        verify(exactly = 1) { model.call(any<Prompt>()) }
    }

    private fun client(): ChatClient = ChatClient.builder(model)
        .defaultAdvisors(ToolAllowlistAdvisor(allowlist))
        .build()

    private fun responses(): List<ChatResponse> = listOf(
        toolResponse(
            AssistantMessage.ToolCall("1", "function", "Bash", """{"command":"pwd"}"""),
            AssistantMessage.ToolCall("2", "function", "Bash", """{"command":"pwd; id"}"""),
            AssistantMessage.ToolCall("3", "function", "Write", "{}"),
            AssistantMessage.ToolCall("4", "function", "Edit", "{}"),
            AssistantMessage.ToolCall("5", "function", "CustomTool", "{}"),
        ),
        toolResponse(
            AssistantMessage.ToolCall("6", "function", "Read", "{}"),
            AssistantMessage.ToolCall("7", "function", "Bash", """{"command":"id"}"""),
            AssistantMessage.ToolCall("8", "function", "Bash", """{"command":"pwd","runInBackground":true}"""),
        ),
        ChatResponse(listOf(Generation(AssistantMessage("Review complete")))),
    )

    private fun toolResponse(vararg calls: AssistantMessage.ToolCall): ChatResponse =
        ChatResponse(listOf(Generation(AssistantMessage.builder().toolCalls(calls.toList()).build())))

    private fun callback(name: String, returnDirect: Boolean = name == "Bash"): ToolCallback = object : ToolCallback {
        override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder()
            .name(name).description("Test tool").inputSchema("""{"type":"object"}""").build()

        override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().returnDirect(returnDirect).build()

        override fun call(toolInput: String): String = error("Tool context was lost")

        override fun call(toolInput: String, toolContext: ToolContext?): String {
            executions.add("$name:$toolInput")
            contexts.add(toolContext)
            return "Allowed result"
        }
    }

    private fun assertExecutionsAndFeedback() {
        assertEquals(listOf("Bash:{\"command\":\"pwd\"}", "Write:{}", "Edit:{}", "CustomTool:{}", "Read:{}"), executions)
        assertEquals(List(5) { "/repo" }, contexts.map { it?.context?.get("checkout") })
        assertEquals(3, prompts.size)
        val responses = prompts.last().instructions.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }
        assertEquals((1..8).map(Int::toString), responses.map { it.id() })
        assertEquals(
            listOf("Bash", "Bash", "Write", "Edit", "CustomTool", "Read", "Bash", "Bash"),
            responses.map { it.name() },
        )
        assertEquals(3, responses.count { it.responseData().startsWith("Shell command denied by policy") })
    }
}
