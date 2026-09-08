package org.sift.e2e

import io.fabric8.kubernetes.client.KubernetesClient
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.security.SecureRandom
import java.util.Base64
import kotlin.collections.ArrayDeque
import kotlin.collections.forEach
import kotlin.collections.mapOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * JUnit extension that boots the whole platform once per test JVM and tears it down when the
 * root [ExtensionContext.Store] closes (also after failures): preflight → Compose → kind cluster →
 * manifests → credentials → operator JVM → server JVM. Test classes receive the extension itself
 * as a constructor/method parameter and talk to the server only through [serverBaseUrl].
 */
class SiftEnvironment : BeforeAllCallback, ParameterResolver {
    private lateinit var stack: Stack

    val serverBaseUrl: String get() = stack.serverBaseUrl
    val kubernetesClient: KubernetesClient get() = stack.client
    val jdbcUrl: String get() = Compose.POSTGRES_JDBC_URL
    val preflight: PreflightReport get() = stack.preflight
    val issuerUri: String get() = Compose.KEYCLOAK_ISSUER

    /** A fresh Keycloak access token for the `e2e` user; cached and renewed shortly before expiry. */
    fun accessToken(): String = stack.accessToken()

    /** Server API client authenticated as the `e2e` user (token refreshed per request when needed). */
    fun api(): ServerApi = ServerApi(serverBaseUrl, ::accessToken)

    /** Server API client that sends no bearer token. */
    fun anonymousApi(): ServerApi = ServerApi(serverBaseUrl)

    override fun beforeAll(context: ExtensionContext) {
        stack = context.root.getStore(NAMESPACE)
            .computeIfAbsent(Stack::class.java, { Stack.boot() }, Stack::class.java)
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type == SiftEnvironment::class.java

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any = this

    /** The booted resources; closed by JUnit through [AutoCloseable] when the root store is closed. */
    class Stack private constructor(
        val preflight: PreflightReport,
        private val cluster: KindCluster,
        val client: KubernetesClient,
    ) : AutoCloseable {
        private val processes = ArrayDeque<HostProcess>()
        private val operatorConfig get() = RepoRoot.dir.resolve("k8s/local/operator.yaml")
        val serverBaseUrl: String = "http://127.0.0.1:${preflight.serverPort}"
        private var token: AccessToken? = null

        @Synchronized
        fun accessToken(): String {
            val current = token?.takeIf { it.isFresh() }
                ?: KeycloakTokens.passwordGrant(
                    issuer = Compose.KEYCLOAK_ISSUER,
                    clientId = Compose.KEYCLOAK_CLIENT_ID,
                    username = Compose.E2E_USER,
                    password = Compose.E2E_PASSWORD,
                ).also { token = it }
            return current.value
        }

        private fun startOperator() {
            val env = mapOf(
                "KUBECONFIG" to cluster.kubeconfig.path,
                "SIFT_OPERATOR_NAMESPACE" to ClusterResources.NAMESPACE,
                "SPRING_CONFIG_ADDITIONAL_LOCATION" to operatorConfig.toURI().toString(),
                "SPRING_RABBITMQ_PASSWORD" to Compose.RABBITMQ_PASSWORD,
            )
            val operator = HostProcess.start(name = "operator", module = "operator", env = env)
            processes.addFirst(operator)
            operator.awaitLogLine(STARTED_LOG_LINE, startupBudget)
        }

        private fun startServer() {
            val env = mapOf(
                "KUBECONFIG" to cluster.kubeconfig.path,
                "SIFT_SERVER_NAMESPACE" to ClusterResources.NAMESPACE,
                "SIFT_SERVER_ENCRYPTION_KEY" to randomEncryptionKey(),
                "SERVER_PORT" to preflight.serverPort.toString(),
                "SERVER_ADDRESS" to "127.0.0.1",
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" to Compose.KEYCLOAK_ISSUER,
                "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES" to Compose.KEYCLOAK_AUDIENCE,
                "SIFT_SERVER_AUTH_CLIENT_ID" to Compose.KEYCLOAK_CLIENT_ID,
            )
            val server = HostProcess.start(name = "server", module = "server", env = env)
            processes.addFirst(server)
            server.awaitHttp("$serverBaseUrl/actuator/health/readiness", startupBudget)
        }

        /** Stops the JVMs (most recent first), keeps infra unless `SIFT_E2E_DESTROY=true`, prints log paths. */
        override fun close() {
            processes.forEach { process ->
                runCatching { process.stop() }.onFailure { System.err.println("Failed to stop ${process.name}: $it") }
                println("${process.name} log: ${process.logFile}")
            }
            client.close()
            if (System.getenv(DESTROY_FLAG) == "true") cluster.destroy()
        }

        companion object {
            // Any bootstrap failure must stop already-started JVMs, whatever its type.
            @Suppress("TooGenericExceptionCaught")
            fun boot(): Stack {
                val preflight = Preflight.check()
                preflight.warnings.forEach { println("WARNING: $it") }
                Compose.up()
                val cluster = KindCluster()
                cluster.ensure()
                val client = cluster.client()
                val stack = Stack(preflight, cluster, client)
                try {
                    ClusterResources.describeNodes(client)
                    ClusterResources.install(client)
                    ClusterResources.provisionCredentials(client, preflight.credentials)
                    stack.startOperator()
                    stack.startServer()
                } catch (e: Exception) {
                    stack.close()
                    throw e
                }
                println("Sift e2e environment ready at ${stack.serverBaseUrl}")
                return stack
            }
        }
    }

    companion object {
        const val DESTROY_FLAG = "SIFT_E2E_DESTROY"
        private const val ENCRYPTION_KEY_BYTES = 32
        private val NAMESPACE = ExtensionContext.Namespace.create(SiftEnvironment::class.java)
        private val STARTED_LOG_LINE = Regex("""Started MainKt""")

        /** First run compiles both modules, so the readiness budget is generous. */
        val startupBudget: Duration = 5.minutes

        fun randomEncryptionKey(): String =
            Base64.getEncoder().encodeToString(ByteArray(ENCRYPTION_KEY_BYTES).also(SecureRandom()::nextBytes))
    }
}
