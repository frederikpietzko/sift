package org.sift.agents.review

import org.sift.agents.shared.advisors.ToolAllowlistAdvisor
import org.sift.agents.shared.advisors.ToolCallAllowlist
import org.sift.agents.shared.tools.SearxngSearchTool
import org.sift.agents.shared.tools.WorkingDirectoryShellTool
import org.sift.events.CodeReviewCompletedEvent
import org.sift.events.Finding
import org.slf4j.LoggerFactory
import org.springaicommunity.agent.tools.FileSystemTools
import org.springaicommunity.agent.tools.GlobTool
import org.springaicommunity.agent.tools.GrepTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@Component
class ReviewAgent(
    chatClientBuilder: ChatClient.Builder,
    private val webSearchTool: ObjectProvider<SearxngSearchTool>,
    private val clock: Clock = Clock.systemUTC(),
    private val toolProperties: ReviewToolProperties = ReviewToolProperties(),
) {
    private val chatClient = chatClientBuilder
        .defaultAdvisors(SimpleLoggerAdvisor())
        .build()

    fun review(checkout: Checkout): ReviewResult {
        val allowedShellCommands = resolveAllowedShellCommands(checkout)
        val advisor = ToolAllowlistAdvisor(ToolCallAllowlist(allowedShellCommands = allowedShellCommands))
        val userMessage = buildUserMessage(checkout, allowedShellCommands)
        val tools = buildTools(checkout.dir)
        return try {
            callModel(advisor, userMessage, tools)
        } catch (@Suppress("TooGenericExceptionCaught") exception: RuntimeException) {
            logger.warn("Review call failed, retrying once", exception)
            callModel(advisor, userMessage, tools)
        }
    }

    /**
     * Expands the `{base}` and `{branch}` placeholders of the configured shell command templates
     * with the checkout's branch names. Templates whose placeholders cannot be resolved, or whose
     * expansion is not a plain command accepted by [ToolCallAllowlist], are dropped with a warning
     * instead of failing the review.
     */
    private fun resolveAllowedShellCommands(checkout: Checkout): Set<String> {
        val values = mapOf(
            BASE_PLACEHOLDER to checkout.baseBranch,
            BRANCH_PLACEHOLDER to checkout.branch,
        )
        return toolProperties.allowedShellCommands.mapNotNullTo(linkedSetOf()) { template ->
            val resolved = values.entries.fold<Map.Entry<String, String?>, String?>(template) { command, entry ->
                if (command == null || !command.contains(entry.key)) {
                    command
                } else {
                    entry.value?.let { value -> command.replace(entry.key, value) }
                }
            }
            when {
                resolved == null -> {
                    logger.warn("Dropping shell command template '{}': branch names are not available", template)
                    null
                }
                !ToolCallAllowlist.isSimpleCommand(resolved) -> {
                    logger.warn("Dropping shell command template '{}': expansion is not a plain command", template)
                    null
                }
                else -> resolved
            }
        }
    }

    fun toEvent(properties: ReviewProperties, result: ReviewResult): CodeReviewCompletedEvent =
        CodeReviewCompletedEvent(
            repositoryUrl = properties.repositoryUrl,
            branch = properties.branch,
            baseBranch = properties.baseBranch,
            commitSha = properties.commitSha,
            executionId = properties.executionId,
            pullRequest = properties.pullRequest,
            summary = result.summary,
            findings = result.findings.map { finding ->
                Finding(
                    file = finding.file,
                    startLine = finding.startLine,
                    endLine = finding.endLine,
                    severity = finding.severity,
                    category = finding.category,
                    message = finding.message,
                    suggestion = finding.suggestion,
                )
            },
            completedAt = Instant.now(clock),
        )

    @Suppress("SpreadOperator")
    private fun callModel(advisor: ToolAllowlistAdvisor, userMessage: String, tools: List<Any>): ReviewResult =
        requireNotNull(
            chatClient.prompt()
                .advisors(advisor)
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .tools(*tools.toTypedArray())
                .call()
                .entity(ReviewResult::class.java),
        ) { "The model did not return a structured review result" }

    private fun buildUserMessage(checkout: Checkout, allowedShellCommands: Set<String>): String {
        val diff = checkout.diff
        val cappedDiff = if (diff.length > MAX_DIFF_CHARS) {
            diff.take(MAX_DIFF_CHARS) + TRUNCATION_NOTE
        } else {
            diff
        }
        return """
            |The repository under review is checked out at: ${checkout.dir}
            |
            |Exact allowed shell commands: ${allowedShellCommands.sorted().joinToString().ifEmpty { "none" }}
            |
            |Review the following diff between the base branch and the branch under review:
            |
            |$cappedDiff
        """.trimMargin()
    }

    private fun buildTools(dir: Path): List<Any> {
        val tools = mutableListOf<Any>(
            WorkingDirectoryShellTool(dir.toAbsolutePath()),
            GrepTool.builder().workingDirectory(dir).build(),
            GlobTool.builder().workingDirectory(dir).build(),
            FileSystemTools.builder().allowedDirectory(dir).build(),
        )
        webSearchTool.ifAvailable { tool -> tools.add(tool) }
        return tools
    }

    companion object {
        const val MAX_DIFF_CHARS: Int = 100_000
        const val BASE_PLACEHOLDER: String = "{base}"
        const val BRANCH_PLACEHOLDER: String = "{branch}"
        const val TRUNCATION_NOTE: String =
            "\n\n[Note: the diff was truncated because it exceeded $MAX_DIFF_CHARS characters.]"

        private val logger = LoggerFactory.getLogger(ReviewAgent::class.java)

        private val SYSTEM_PROMPT = """
            You are a thorough and pragmatic code reviewer.

            You are given the diff of a change set. The repository containing the change set is
            checked out locally. Use the available tools (shell, grep, glob, file system, and web
            search if present) to explore the surrounding code and gather the context you need to
            judge the change properly. Only shell commands are restricted by an allowlist. They
            must exactly match a configured command; do not add cd, chaining, substitutions,
            redirections, or background execution. The shell runs in the checkout directory.
            Prefer Read, Grep, and Glob for repository exploration. If a call is denied,
            continue with non-shell tools. Do not attempt to modify the repository.

            Review the change for correctness, security issues, performance problems, error
            handling, readability, and maintainability. Only report findings that are noteworthy
            and actionable; do not invent issues.

            Report your review as structured output:
            - summary: a concise overall assessment of the change set.
            - findings: a list of findings, each with:
              - file: the path of the affected file relative to the repository root.
              - startLine/endLine: the affected line range, if known.
              - severity: one of BLOCKER, MAJOR, MINOR, INFO.
              - category: a short category such as bug, security, performance, or style.
              - message: what is wrong and why it matters.
              - suggestion: a concrete suggestion on how to fix it, if you have one.

            If there is nothing noteworthy to report, return an empty findings list.
        """.trimIndent()
    }
}
