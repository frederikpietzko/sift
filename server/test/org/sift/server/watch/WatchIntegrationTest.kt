package org.sift.server.watch

import org.sift.server.PostgresIntegrationTest
import org.sift.server.agents.AgentKind
import org.sift.server.agents.AgentPhase
import org.sift.server.agents.AgentRun
import org.sift.server.agents.AgentRunRepository
import org.sift.server.agents.RunSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionOperations
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [PostgresIntegrationTest] with the notification listener switched on and a real servlet container (for the SSE
 * end-to-end test). Both watch integration tests share this exact configuration so Spring caches one context.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.rabbitmq.dynamic=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "sift.server.messaging.consumers-enabled=false",
        "sift.server.watch.enabled=true",
        "sift.server.watch.heartbeat=1s",
        "sift.server.encryption-key=\${sift.test.encryption-key}",
    ],
)
abstract class WatchIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    protected lateinit var runs: AgentRunRepository

    @Autowired
    protected lateinit var transactions: TransactionOperations

    private val mapper = JsonMapper.builder().build()

    /** Inserts and commits a `CREATED` run, which fires the `agent_runs_notify` trigger. */
    protected fun insertRun(): AgentRun {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        val run = AgentRun(
            id = id,
            kind = AgentKind.CODE_REVIEW,
            source = RunSource.API,
            repositoryId = null,
            crName = "cr-$id",
            crUid = null,
            generation = null,
            executionId = null,
            phase = AgentPhase.CREATED,
            reason = null,
            message = null,
            spec = mapper.createObjectNode().put("branch", "feature/watch"),
            createdAt = now,
            startedAt = null,
            completedAt = null,
            observedAt = null,
            updatedAt = now,
        )
        return transactions.execute { runs.insert(run) }
    }

    /** Commits a phase change; `updatedAt` moves forward so the SSE id changes. */
    protected fun advance(run: AgentRun, phase: AgentPhase): AgentRun {
        val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
        return transactions.execute { runs.updateStatus(run.copy(phase = phase, observedAt = now, updatedAt = now)) }
    }
}
