package org.sift.e2e

import kotlin.time.Duration.Companion.minutes

/**
 * Starts the Compose-managed infrastructure the server, operator and in-cluster agent expect on
 * the host (`compose.yaml` at the repo root). Containers are left running for fast re-runs.
 */
object Compose {
    val services: List<String> = ["postgres", "rabbitmq"]
    const val POSTGRES_JDBC_URL = "jdbc:postgresql://localhost:5432/sift"
    const val POSTGRES_USER = "sift"
    const val POSTGRES_PASSWORD = "sift"
    const val RABBITMQ_PASSWORD = "sift"

    /** `docker compose up -d --wait postgres rabbitmq`: idempotent, blocks until health checks pass. */
    fun up() {
        Shell.runOrThrow("docker", "compose", "up", "-d", "--wait", "postgres", "rabbitmq", timeout = 5.minutes)
    }
}
