package org.sift.server

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.rabbitmq.RabbitMQContainer

/**
 * [PostgresIntegrationTest] plus a Testcontainers RabbitMQ: overrides the base class's "listeners down"
 * properties so the `RabbitAdmin` declares the server topology and the `@RabbitListener` consumers run. The
 * broker is a JVM singleton like the Postgres container so every subclass shares one Spring context.
 */
@SpringBootTest(
    properties = [
        "sift.server.watch.enabled=false",
        "sift.server.encryption-key=\${sift.test.encryption-key}",
    ],
)
abstract class RabbitMqIntegrationTest : PostgresIntegrationTest() {
    companion object {
        @ServiceConnection
        @JvmStatic
        val rabbit: RabbitMQContainer = RabbitMQContainer("rabbitmq:4-management-alpine")

        init {
            rabbit.start()
        }
    }
}
