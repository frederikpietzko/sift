package org.sift.e2e

import kotlin.time.Duration.Companion.minutes

/**
 * Starts the Compose-managed infrastructure the server, operator and in-cluster agent expect on
 * the host (`compose.yaml` at the repo root). Containers are left running for fast re-runs.
 */
object Compose {
    val services: List<String> = ["postgres", "rabbitmq", "keycloak"]
    const val POSTGRES_JDBC_URL = "jdbc:postgresql://localhost:5432/sift"
    const val POSTGRES_USER = "sift"
    const val POSTGRES_PASSWORD = "sift"
    const val RABBITMQ_PASSWORD = "sift"

    /** Issuer of the imported `sift` realm (`config/keycloak/sift-realm.json`). */
    const val KEYCLOAK_ISSUER = "http://localhost:8180/realms/sift"
    const val KEYCLOAK_CLIENT_ID = "sift-web"
    const val KEYCLOAK_AUDIENCE = "sift-server"
    const val E2E_USER = "e2e"
    const val E2E_PASSWORD = "e2e"

    /** `docker compose up -d --wait <services>`: idempotent, blocks until health checks pass. */
    fun up() {
        Shell.runOrThrow(
            "docker", "compose", "up", "-d", "--wait", "postgres", "rabbitmq", "keycloak",
            timeout = 5.minutes,
        )
    }
}
