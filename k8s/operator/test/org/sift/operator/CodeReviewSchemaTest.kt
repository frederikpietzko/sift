package org.sift.operator

import io.fabric8.crdv2.generator.CRDGenerator
import io.fabric8.kubernetes.api.model.ConditionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import org.junit.jupiter.api.io.TempDir
import org.sift.crds.CodeReview
import org.sift.crds.Phase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeReviewSchemaTest {
    @TempDir
    lateinit var output: Path

    private val serialization = KubernetesSerialization()
    private val manifestName = "codereviews.sift.org-v1.yml"
    private val projectRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("project.yaml")) }
    private val manifest = projectRoot.resolve("k8s/manifests/crds/$manifestName")
    private val crd = serialization.unmarshal(Files.readString(manifest), CustomResourceDefinition::class.java)
    private val version = crd.spec.versions.single()
    private val schema = version.schema.openAPIV3Schema

    @Test
    fun `checked in manifest matches current model generation`() {
        CRDGenerator().customResourceClasses(CodeReview::class.java).inOutputDir(output.toFile()).detailedGenerate()
        val generated = serialization.unmarshal(
            Files.readString(output.resolve(manifestName)), CustomResourceDefinition::class.java,
        )
        assertEquals(crd, generated)
    }

    @Test
    fun `spec requires base branch and full SHA while preserving optional pull request`() {
        val spec = schema.properties.getValue("spec")
        assertEquals(setOf("repositoryUrl", "branch", "baseBranch", "commitSha"), spec.required.toSet())
        assertEquals("string", spec.properties.getValue("pullRequest").type)
        val shaPattern = Regex(spec.properties.getValue("commitSha").pattern)
        assertTrue(shaPattern.matches("a1".repeat(20)))
        assertTrue(shaPattern.matches("AB".repeat(20)))
        listOf("", "abc1234", "g".repeat(40), "a".repeat(41), " ${"a".repeat(40)}").forEach {
            assertFalse(shaPattern.matches(it), "Invalid commit SHA accepted: $it")
        }
        listOf("repositoryUrl", "branch", "baseBranch").forEach { field ->
            val pattern = Regex(spec.properties.getValue(field).pattern)
            assertFalse(pattern.matches(""))
            assertFalse(pattern.matches("   "))
            assertFalse(pattern.matches("branch name"))
            assertTrue(pattern.matches("feature/review"))
        }
    }

    @Test
    fun `status preserves phases and exposes execution identity resource UIDs and conditions`() {
        assertEquals("Namespaced", crd.spec.scope)
        assertEquals("v1alpha1", version.name)
        assertNotNull(version.subresources.status)
        val status = schema.properties.getValue("status").properties
        assertEquals(Phase.entries.map { it.name }.toSet(), status.getValue("phase").enum.map { it.asText() }.toSet())
        assertEquals("integer", status.getValue("observedGeneration").type)
        listOf("executionId", "commitSha", "startedAt", "completedAt", "message").forEach {
            assertEquals("string", status.getValue(it).type)
        }
        listOf("jobRef", "configMapRef").forEach {
            assertEquals(setOf("name", "uid"), status.getValue(it).required.toSet())
        }
        val conditions = status.getValue("conditions")
        assertEquals("array", conditions.type)
        assertEquals(
            setOf("type", "status", "reason", "message", "lastTransitionTime", "observedGeneration"),
            conditions.items.schema.properties.keys,
        )
    }

    @Test
    fun `Fabric8 round trips the execution contract without a Jackson Kotlin module`() {
        val review = CodeReview().apply {
            metadata.name = "review"
            metadata.namespace = "sift-test"
            metadata.uid = "test-uid"
            metadata.generation = 2L
            spec = CodeReview.Spec(
                repositoryUrl = "https://github.com/example/repository",
                branch = "feature/review",
                baseBranch = "main",
                commitSha = "a".repeat(40),
                pullRequest = "1",
            )
            status = CodeReview.Status(
                phase = Phase.SUCCESS,
                observedGeneration = 2L,
                executionId = "test-uid:2",
                commitSha = spec.commitSha,
                jobRef = CodeReview.ResourceReference(name = "review-job", uid = "job-uid"),
                configMapRef = CodeReview.ResourceReference(name = "review-config", uid = "config-uid"),
                startedAt = "2026-09-06T00:00:00Z",
                completedAt = "2026-09-06T00:01:00Z",
                conditions = listOf(
                    ConditionBuilder().withType("Complete").withStatus("True").withReason("JobCompleted")
                        .withMessage("Review Job completed").withObservedGeneration(2L)
                        .withLastTransitionTime("2026-09-06T00:01:00Z").build(),
                ),
            )
        }
        val restored = serialization.unmarshal(serialization.asJson(review), CodeReview::class.java)
        assertEquals(review.spec, restored.spec)
        assertEquals(review.status, restored.status)
        assertEquals(review.metadata, restored.metadata)
    }
}
