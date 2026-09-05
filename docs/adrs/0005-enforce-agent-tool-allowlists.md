# ADR 0005: Enforce Agent Shell Command Allowlists

Date: 2026-09-06

## Status

Accepted

## Context

The code-review agent exposes tools to a model reading untrusted repository content. Prompt
instructions alone cannot prevent unwanted shell execution. The required enforcement boundary
is shell commands, not general tool permissions.

## Decision

Implement `ToolAllowlistAdvisor` and `ToolCallAllowlist` in `agents/shared`, and explicitly attach
the advisor to the code-review client. The advisor specializes Spring AI's `ToolCallingAdvisor`
with a guarded execution manager, so permission checks run before callbacks execute in every
blocking or streaming tool-call round, not after the model's tool loop has finished.

- Wrap only registered shell callbacks. Non-shell tools pass through unchanged, including
  writes and edits; unknown tools follow Spring AI's normal resolution/error handling.
- For shell tools, require an exact, case-sensitive match of the entire `command` argument
  against trusted configuration. Do not normalize whitespace, match prefixes, or use patterns.
- Accept only simple command strings without quoting, expansions, operators, or control
  characters. Reject malformed/ambiguous JSON, unknown shell fields, and background execution.
- Return denied calls as tool results, preserving call IDs and allowing the review to continue.
  Shell results go back to the model, overriding shell `returnDirect` metadata. Non-shell
  metadata is preserved. Retain Spring AI's conversation handling and default tool-call
  limits, including for denied attempts.
- Configure shell commands per agent rather than globally auto-configuring every chat client.
  Code review defaults to an empty shell-command allowlist; there is no general tool allowlist.

## Alternatives

- Prompt-only restrictions are not an execution boundary.
- A general tool-name allowlist unnecessarily restricts non-shell tools.
- A response advisor outside the tool loop runs too late to prevent side effects.
- Command-name/prefix matching permits dangerous arguments and shell composition.
- Aborting on every denied call prevents the model from recovering with permitted tools.

## Consequences

Operators must explicitly approve shell commands. An approved command is a trusted capability:
scripts, interpreters, Git configuration, executable lookup, and inherited environment can still
cause side effects. This is not an OS sandbox, a filesystem boundary, or an egress policy.
Deployment isolation and careful command selection remain necessary.

The shared policy recognizes `Bash` by default; consumers exposing additional shell tools must
declare their names, register their callbacks, and use the same argument schema or implement
an appropriate policy first. Other tools are governed by their own implementations, not by
this shell-command allowlist.
