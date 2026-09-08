package org.sift.server.users.persistence

import org.springframework.modulith.NamedInterface
import org.springframework.modulith.PackageInfo

/** Exposed as `users :: persistence` so the `agents` read model can left-join `UsersTable`. */
@NamedInterface("persistence")
@PackageInfo
class ModuleMetadata
