package org.sift.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots the full stack through [SiftEnvironment] (operator + server as host JVMs) and checks the
 * server is reachable and the cluster is prepared. Skipped unless `SIFT_E2E=true`.
 */
@EnabledIfEnvironmentVariable(named = "SIFT_E2E", matches = "true")
@ExtendWith(SiftEnvironment::class)
class SiftEnvironmentSmokeTest(private val env: SiftEnvironment) {

    @Test
    fun `operator and server are running against the dedicated cluster`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("${env.serverBaseUrl}/actuator/health/readiness")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())

        val crd = env.kubernetesClient.apiextensions().v1().customResourceDefinitions()
            .withName(ClusterResources.CODE_REVIEW_CRD).get()
        assertTrue(crd != null, "CodeReview CRD should be installed")
        assertTrue(env.serverBaseUrl.startsWith("http://127.0.0.1:"))
    }
}
