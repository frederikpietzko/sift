package org.sift.server.results

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Review results ingested from `code-review.completed`; links to and completes runs through `AgentRunService`. */
@ApplicationModule(allowedDependencies = ["api", "config", "agents"])
@PackageInfo
class ModuleMetadata
