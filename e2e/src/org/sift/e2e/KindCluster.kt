package org.sift.e2e

import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * A dedicated kind cluster for the e2e suite. It has its own kubeconfig under `build/e2e` and
 * never touches the root `.kubeconfig` or the developer's `kind-kind` cluster (ADR 0009 exception,
 * see ADR 0015).
 */
class KindCluster(
    val name: String = DEFAULT_NAME,
    val kubeconfig: File = RepoRoot.e2eBuildDir.resolve("kubeconfig"),
) {
    val context: String get() = "kind-$name"

    /** Creates the cluster if absent, otherwise re-exports its kubeconfig. Returns true when created. */
    fun ensure(): Boolean {
        kubeconfig.parentFile.mkdirs()
        val created = !exists()
        if (created) {
            Shell.runOrThrow(
                "kind", "create", "cluster", "--name", name, "--kubeconfig", kubeconfig.path, "--wait", "2m",
                timeout = 10.minutes,
            )
        } else {
            Shell.runOrThrow("kind", "export", "kubeconfig", "--name", name, "--kubeconfig", kubeconfig.path)
        }
        return created
    }

    fun exists(): Boolean =
        Shell.runOrThrow("kind", "get", "clusters").stdout.lineSequence().any { it.trim() == name }

    /** Deletes the cluster and its kubeconfig; a no-op when the cluster does not exist. */
    fun destroy() {
        if (exists()) {
            Shell.runOrThrow(
                "kind", "delete", "cluster", "--name", name, "--kubeconfig", kubeconfig.path,
                timeout = 5.minutes,
            )
        }
        kubeconfig.delete()
    }

    /** A client bound exclusively to this cluster's kubeconfig; callers own closing it. */
    fun client(): KubernetesClient {
        if (!kubeconfig.isFile) {
            throw E2ePreconditionException("Kubeconfig ${kubeconfig.path} is missing; call ensure() first")
        }
        val config = Config.fromKubeconfig(kubeconfig.readText())
        val actual = config.currentContext?.name
        if (actual != context) {
            throw E2ePreconditionException("Kubeconfig ${kubeconfig.path} points at context $actual, expected $context")
        }
        return KubernetesClientBuilder().withConfig(config).build()
    }

    companion object {
        const val DEFAULT_NAME = "sift-e2e"
    }
}
