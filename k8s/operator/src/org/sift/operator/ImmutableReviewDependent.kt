package org.sift.operator

import io.fabric8.kubernetes.api.model.HasMetadata
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.dependent.GarbageCollected
import io.javaoperatorsdk.operator.processing.dependent.Creator
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResource
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependentResourceConfigBuilder
import org.sift.crds.CodeReview
import java.util.Optional

abstract class ImmutableReviewDependent<R : HasMetadata>(private val type: Class<R>) :
    KubernetesDependentResource<R, CodeReview>(type), Creator<R, CodeReview>, GarbageCollected<CodeReview> {
    init {
        configureWith(KubernetesDependentResourceConfigBuilder<R>().withUseSSA(false).build())
    }

    abstract override fun desired(primary: CodeReview, context: Context<CodeReview>): R

    fun validateDesired(primary: CodeReview, context: Context<CodeReview>) {
        desired(primary, context)
    }

    override fun create(desired: R, primary: CodeReview, context: Context<CodeReview>): R {
        val latest = context.client.resources(CodeReview::class.java)
            .inNamespace(primary.metadata.namespace).withName(primary.metadata.name).get()
        check(latest?.metadata?.uid == primary.metadata.uid &&
            latest.metadata.generation == primary.metadata.generation &&
            latest.metadata.resourceVersion == primary.metadata.resourceVersion &&
            latest.metadata.deletionTimestamp == null) {
            "Execution changed before dependent creation; reconcile the latest resource"
        }
        return super.create(desired, primary, context)
    }

    override fun getSecondaryResource(primary: CodeReview, context: Context<CodeReview>): Optional<R> {
        val execution = ReviewExecution(primary)
        val actual = context.client.resources(type).inNamespace(execution.namespace).withName(execution.name).get()
        actual?.let { execution.requireOwned(it) }
        return Optional.ofNullable(actual)
    }
}
