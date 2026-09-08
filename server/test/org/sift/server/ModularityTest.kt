package org.sift.server

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Enforces the package structure of the server: every direct sub-package of `org.sift.server` is a Spring
 * Modulith application module whose `ModuleMetadata` declares the modules it may depend on. Other modules may
 * only reference a module's base package (its API) or an explicitly `@NamedInterface`d sub-package; the
 * `persistence`, `web`, `messaging`, `adapters`, `secrets` and `watch` sub-packages are internal.
 *
 * Test classes are excluded explicitly: ArchUnit's default only recognises Maven/Gradle test output directories,
 * not the Kotlin toolchain's, and test fixtures (`TestTokens`, `TestUsers`, `PostgresIntegrationTest`) are
 * deliberately shared across modules.
 */
class ModularityTest {
    private val modules = ApplicationModules.of(Application::class.java, TestClasses)

    @Test
    fun `module structure has no cycles and no access to internals`() {
        modules.verify()
    }

    @Test
    fun `every expected module is detected`() {
        val names = modules.map { it.identifier.toString() }.toSet()
        assertEquals(setOf("agents", "api", "config", "repositories", "results", "security", "users"), names)
    }

    @Test
    fun `module documentation can be generated`() {
        Documenter(modules, Documenter.Options.defaults().withOutputFolder(DOCS_OUTPUT)).writeDocumentation()
    }

    companion object {
        /** Relative to the working directory of the test run; `build/` is git-ignored. */
        private const val DOCS_OUTPUT = "build/spring-modulith-docs"
    }

    /** Everything compiled into the same output directory as this test class (production code comes from a jar). */
    private object TestClasses : DescribedPredicate<JavaClass>("test classes") {
        private val testOutput: Path = Path.of(ModularityTest::class.java.protectionDomain.codeSource.location.toURI())

        override fun test(input: JavaClass): Boolean = input.source
            .map { source -> source.uri.scheme == "file" && Path.of(source.uri).startsWith(testOutput) }
            .orElse(false)
    }
}
