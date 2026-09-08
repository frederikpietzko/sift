package org.sift.server.api

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** Shared web contract (RFC 7807 errors, paging); depends on no other module. */
@ApplicationModule(allowedDependencies = [])
@PackageInfo
class ModuleMetadata
