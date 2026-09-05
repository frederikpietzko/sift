package org.sift.agents.shared.advisors

import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.ToolExecutionResult
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

internal class AllowlistToolCallingManager(private val allowlist: ToolCallAllowlist) : ToolCallingManager {
    private val delegate = ToolCallingManager.builder().build()

    override fun resolveToolDefinitions(chatOptions: ToolCallingChatOptions): List<ToolDefinition> =
        delegate.resolveToolDefinitions(chatOptions)

    override fun executeToolCalls(prompt: Prompt, chatResponse: ChatResponse): ToolExecutionResult {
        val options = requireNotNull(prompt.options as? ToolCallingChatOptions)
        val guardedCallbacks = options.toolCallbacks.orEmpty().map { callback ->
            if (allowlist.isShellTool(callback.toolDefinition.name())) {
                GuardedToolCallback(callback, allowlist)
            } else {
                callback
            }
        }
        val guardedOptions = options.mutate().toolCallbacks(guardedCallbacks).build()
        return delegate.executeToolCalls(Prompt(prompt.instructions, guardedOptions), chatResponse)
    }

    private class GuardedToolCallback(
        private val callback: ToolCallback,
        private val allowlist: ToolCallAllowlist,
    ) : ToolCallback {
        override fun getToolDefinition(): ToolDefinition = callback.toolDefinition

        override fun call(toolInput: String): String = call(toolInput, null)

        override fun call(toolInput: String, toolContext: ToolContext?): String =
            if (allowlist.allows(toolDefinition.name(), toolInput)) {
                callback.call(toolInput, toolContext)
            } else {
                "Shell command denied by policy. The command or its arguments are not allowed. " +
                    "Use an allowed shell command or a non-shell tool; this command was not executed."
            }
    }
}
