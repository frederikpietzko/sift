package org.sift.e2e

import java.io.File

/**
 * Locates the repository root (the directory containing `project.yaml` and the `kotlin`
 * wrapper) from the current working directory, so the harness works whether the tests are
 * launched from the root or from the `e2e` module directory.
 */
object RepoRoot {
    val dir: File by lazy { locate(File(System.getProperty("user.dir")).absoluteFile) }

    /** `build/e2e` below the repo root: kubeconfig and child-process logs live here. */
    val e2eBuildDir: File get() = dir.resolve("build/e2e")

    private fun locate(start: File): File =
        generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "project.yaml").isFile && File(it, "kotlin").isFile }
            ?: throw E2ePreconditionException(
                "Cannot locate repository root (project.yaml + kotlin wrapper) from $start",
            )
}
