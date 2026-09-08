package org.sift.server

import com.ninjasquad.springmockk.MockkBean
import io.fabric8.kubernetes.client.KubernetesClient
import org.sift.server.security.TestSecurityConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.util.Base64

/**
 * Shared Spring Boot + Testcontainers Postgres setup: RabbitMQ listeners stay down (consumers are not even
 * registered, because a paused-and-restarted cached context would start them regardless of `auto-startup`),
 * the Postgres `LISTEN` coroutine stays off (watch tests enable it explicitly), the fabric8 client is a relaxed
 * mock, JWTs are decoded by [TestSecurityConfiguration] (no identity provider) and a random AES-256 key is
 * generated once per JVM. The container is a JVM singleton (not `@Container` managed) so the cached Spring context
 * keeps a live database across test classes; Ryuk reaps it.
 */
@SpringBootTest(
    properties = [
        "spring.rabbitmq.dynamic=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "sift.server.messaging.consumers-enabled=false",
        "sift.server.watch.enabled=false",
        "sift.server.encryption-key=\${sift.test.encryption-key}",
    ],
)
@Import(TestSecurityConfiguration::class)
abstract class PostgresIntegrationTest {
    @MockkBean(relaxed = true)
    protected lateinit var kubernetesClient: KubernetesClient

    companion object {
        val ENCRYPTION_KEY: String = ByteArray(KEY_BYTES)
            .also(SecureRandom()::nextBytes)
            .let(Base64.getEncoder()::encodeToString)

        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        init {
            postgres.start()
            System.setProperty("kubernetes.disable.autoConfig", "true")
            System.setProperty("sift.test.encryption-key", ENCRYPTION_KEY)
        }
    }
}

private const val KEY_BYTES = 32
