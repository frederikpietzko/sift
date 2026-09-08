package org.sift.server.security

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/** OAuth2 resource server setup; wires the `users` provisioning filter into the security filter chain. */
@ApplicationModule(allowedDependencies = ["config", "users"])
@PackageInfo
class ModuleMetadata
