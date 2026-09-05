package org.sift.agents.shared.advisors

import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCallAllowlistTest {
    private val allowlist = ToolCallAllowlist(
        allowedShellCommands = setOf("git --no-pager status --short"),
    )
    private val jsonMapper = JsonMapper.builder().build()

    @Test
    fun `tools and shell commands are denied by default`() {
        assertFalse(ToolCallAllowlist().allows("Bash", command("pwd")))
        listOf("Read", "Write", "Edit", "read", "BashOutput", "KillShell", "Unknown").forEach { name ->
            assertTrue(ToolCallAllowlist().allows(name, "{}"), name)
            assertTrue(allowlist.allows(name, "not JSON"), name)
        }
    }

    @Test
    fun `shell command requires both tool permission and exact command match`() {
        val approved = "git --no-pager status --short"
        assertTrue(allowlist.allows("Bash", command(approved)))
        assertTrue(
            allowlist.allows(
                "Bash",
                """{"command":"$approved","timeout":1000,"description":"inspect status","runInBackground":false}""",
            ),
        )
        listOf("$approved ", " $approved", "$approved --branch", "git status", "GIT --no-pager status --short")
            .forEach { assertFalse(allowlist.allows("Bash", command(it)), it) }
    }

    @Test
    fun `shell syntax cannot bypass exact matching or be configured`() {
        listOf(
            "pwd; id", "pwd && id", "pwd || id", "pwd | sh", "pwd > file", "pwd < file", "pwd &",
            "pwd\nid", "pwd\rid", "pwd\tid", "pwd\u0000", "pwd \$(id)", "pwd `id`", "pwd \$HOME",
            "pwd *", "pwd ?", "pwd ~", "pwd {a,b}", "pwd [a]", "pwd #comment", "pwd \\id",
            "sh -c 'id'", "sh -c \"id\"", "pwd <(id)",
        ).forEach { command ->
            assertFalse(allowlist.allows("Bash", command(command)), command)
            assertFailsWith<IllegalArgumentException>(command) {
                ToolCallAllowlist(allowedShellCommands = setOf(command))
            }
        }
        listOf("", " ").forEach { command ->
            assertFailsWith<IllegalArgumentException> { ToolCallAllowlist(allowedShellCommands = setOf(command)) }
        }
    }

    @Test
    fun `invalid and ambiguous shell arguments fail closed`() {
        val approved = "git --no-pager status --short"
        listOf(
            "", "{", "null", "[]", "{}", "{\"command\":null}", "{\"command\":1}",
            """{"command":["$approved"]}""",
            """{"command":"$approved","command":"id"}""",
            """{"command":"id","command":"$approved"}""",
            """{"command":"$approved"} {}""",
            """{"command":"$approved","runInBackground":true}""",
            """{"command":"$approved","runInBackground":"false"}""",
            """{"command":"$approved","run_in_background":true}""",
            """{"command":"$approved","env":{"PATH":"/tmp"}}""",
            """{"command":"$approved","timeout":0}""",
            """{"command":"$approved","timeout":600001}""",
            """{"command":"$approved","timeout":1.5}""",
            """{"command":"$approved","timeout":"1000"}""",
            """{"command":"$approved","description":{}}""",
        ).forEach { assertFalse(allowlist.allows("Bash", it), it) }
    }

    @Test
    fun `configuration is snapshotted and additional shell tool names can be guarded`() {
        val commands = mutableSetOf("pwd")
        val shells = mutableSetOf("Terminal")
        val policy = ToolCallAllowlist(allowedShellCommands = commands, shellTools = shells)
        commands.add("id")
        shells.clear()
        assertTrue(policy.allows("Terminal", command("pwd")))
        assertFalse(policy.allows("Terminal", command("id")))
        assertTrue(policy.allows("Write", "{}"))
    }

    private fun command(value: String): String = jsonMapper.writeValueAsString(mapOf("command" to value))
}
