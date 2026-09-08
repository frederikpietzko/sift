package org.sift.server.repositories

import java.util.UUID

/**
 * SPI for modules that hold on to a repository (e.g. `agents` with its runs): implementations are consulted by
 * [RepositoryService.delete] and veto the deletion by returning a short description of what still uses the
 * repository (`"active agent runs"`), or `null` when nothing does. Keeps `repositories` free of a dependency
 * on the modules that reference it.
 */
fun interface RepositoryUsageCheck {
    fun usage(repositoryId: UUID): String?
}
