package org.sift.server.config

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Cross-cutting configuration (properties, coroutines, k8s client, queue topology); depends on no other module. */
@ApplicationModule(allowedDependencies = [])
@PackageInfo
class ModuleMetadata
