package org.sift.agents.shared.advisors

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

class ToolCallAllowlist(
    allowedShellCommands: Set<String> = emptySet(),
    shellTools: Set<String> = setOf("Bash"),
) {
    private val allowedShellCommands = allowedShellCommands.toSet()
    private val shellTools = shellTools.toSet()

    init {
        require(this.allowedShellCommands.all { SIMPLE_COMMAND.matches(it) && it.isNotBlank() }) {
            "Shell allowlist entries must be non-blank commands without shell metacharacters or quoting"
        }
    }

    fun isShellTool(toolName: String): Boolean = toolName in shellTools

    fun allows(toolName: String, arguments: String): Boolean =
        !isShellTool(toolName) || allowsShell(arguments)

    private fun allowsShell(arguments: String): Boolean = parseArguments(arguments)?.let(::allowsShellInput) ?: false

    private fun parseArguments(arguments: String): Map<*, *>? = try {
        jsonMapper.readValue(arguments, Map::class.java)
    } catch (_: JacksonException) {
        null
    }

    private fun allowsShellInput(input: Map<*, *>): Boolean {
        val command = input["command"] as? String ?: return false
        val timeout = input["timeout"]
        val validTimeout = timeout == null ||
            ((timeout is Int || timeout is Long) && (timeout as Number).toLong() in 1..MAX_TIMEOUT_MS)
        return input.keys.all { it in SHELL_FIELDS } &&
            command in allowedShellCommands &&
            (input["runInBackground"] == null || input["runInBackground"] == false) &&
            (input["description"] == null || input["description"] is String) && validTimeout
    }

    private companion object {
        const val MAX_TIMEOUT_MS = 600_000L
        val SIMPLE_COMMAND = Regex("[a-zA-Z0-9_./:=,@%+ -]+")
        val SHELL_FIELDS = setOf("command", "timeout", "description", "runInBackground")
        val jsonMapper: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }
}
