package org.sift.server.agents

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Agent runs: REST API, `CodeReview` CR adapter, status-event consumer and the SSE watch. Depends on
 * `repositories` (run creation) and `users` (creator attribution, including the `users` table for the join).
 */
@ApplicationModule(allowedDependencies = ["api", "config", "repositories", "users", "users :: persistence"])
@PackageInfo
class ModuleMetadata
