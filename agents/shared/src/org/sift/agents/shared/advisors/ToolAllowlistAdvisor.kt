package org.sift.agents.shared.advisors

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor

class ToolAllowlistAdvisor(allowlist: ToolCallAllowlist) : ToolCallingAdvisor(
    AllowlistToolCallingManager(allowlist),
    { response -> response.hasToolCalls() },
    DEFAULT_ORDER,
    true,
)
