package org.sift.server.repositories

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Registered repositories and their credentials. Other modules plug into deletion via [RepositoryUsageCheck]
 * instead of this module depending on them.
 */
@ApplicationModule(allowedDependencies = ["api", "config"])
@PackageInfo
class ModuleMetadata
