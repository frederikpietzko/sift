# Shell Command Allowlist Advisor

`agents/shared` provides `ToolAllowlistAdvisor` and `ToolCallAllowlist` in
`org.sift.agents.shared.advisors` (see [ADR 0005](../adrs/0005-enforce-agent-tool-allowlists.md)).

## Usage

Attach the advisor explicitly to each protected `ChatClient`; importing the shared module alone
does not enforce permissions. Use it as the client's sole tool-execution advisor, rather than
adding another `ToolCallingAdvisor` that could execute callbacks without the guard.

```kotlin
val advisor = ToolAllowlistAdvisor(
    ToolCallAllowlist(
        allowedShellCommands = setOf("pwd"),
    ),
)
val client = chatClientBuilder.defaultAdvisors(advisor).build()
```

The shell-command allowlist is empty by default. Configuration is snapshotted on construction
and names are case-sensitive. Only registered shell callbacks are wrapped; non-shell tools
pass through unchanged, with no tool-name allowlist. Unknown tools follow Spring AI's normal
resolution/error handling. The policy checks shell calls in every batch and subsequent tool
round, for blocking and streaming clients. Calls are not authorized by prompt content or
model-supplied tool context.

Denied calls do not invoke the underlying callback. Their tool results preserve the requested
ID and name and explain that execution was denied. Allowed calls retain their arguments and
tool context. Shell results are fed back to the model (`returnDirect` is not honored for shell
callbacks). Non-shell callbacks retain their metadata, including `returnDirect`. Spring AI's
default call limits still apply, including to repeated denials.

## Shell Policy

`shellTools` defaults to `setOf("Bash")`. Consumers with differently named shell callbacks must
include those names; all configured shell callbacks must accept the same argument schema and
be registered as callbacks on the client/request.

- `command`: required string, exactly matching a configured full command.
- `timeout`: optional/null integer in the range 1–600000 milliseconds.
- `description`: optional/null string; never used for authorization.
- `runInBackground`: absent, null, or `false` only.

Unknown fields, duplicate JSON keys, trailing JSON values, wrong types, and malformed JSON
are denied. Configuration entries allow only ASCII letters, digits, spaces, and
`_ . / : = , @ % + -`. Quoting, newlines, shell operators, substitutions, redirections,
wildcards, and escapes are rejected at policy construction. There is no prefix matching,
trimming, path expansion, or shell parsing.

## Working-Directory Shell Tool

`org.sift.agents.shared.tools.WorkingDirectoryShellTool` is a Kotlin port of the
spring-ai-agent-utils `ShellTools` for agents that must run shell commands inside a specific
directory (for example a repository checkout) rather than the process working directory. It
mirrors the upstream tools one to one — `Bash` (`command`, `timeout`, `description`,
`runInBackground`), `BashOutput` (`bash_id`, `filter`) and `KillShell` (`bash_id`) — with the
same output format (`bash_id:` header, separate `STDERR:` section, `Exit code:` only on
non-zero exit, truncation at 30 000 characters, default timeout 120 s / max 600 s), so the
shell policy above applies unchanged. The only behavioural differences are that every command
starts in the configured absolute directory and background processes are tracked per tool
instance instead of in a static registry. Because the policy denies `runInBackground=true`,
`BashOutput`/`KillShell` have nothing to act on for review agents.

`ToolCallAllowlist.isSimpleCommand(command)` exposes the configuration-entry validation so
callers that derive commands at runtime (for example by expanding placeholders) can drop
entries that would otherwise fail policy construction.

## Limits of Protection

This is execution authorization, not a sandbox. An explicitly allowed command can still run
scripts, invoke helpers, read secrets, mutate files, or use the network. Review commands and
their environment before approving them; prefer absolute executable paths and avoid repository
scripts and unrestricted interpreters. Enforce filesystem and network isolation at deployment.
Non-shell tools, including file writes and edits, are not restricted by this advisor. Tool
registration and shell-command configuration must come from trusted application code/configuration.
