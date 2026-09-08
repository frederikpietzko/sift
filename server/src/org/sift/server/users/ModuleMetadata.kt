package org.sift.server.users

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Authenticated identities. Exposes the domain model, `UserService`, `@CurrentUser` and the provisioning filter
 * from this package; `persistence` is a named interface so `agents` can join `users` for the creator's username.
 */
@ApplicationModule(allowedDependencies = ["api", "config"])
@PackageInfo
class ModuleMetadata
