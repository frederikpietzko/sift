package org.sift.agents.shared.messaging

import org.sift.events.SiftEvent

/**
 * Publishes [SiftEvent]s to the messaging infrastructure.
 */
interface EventPublisher {
    /**
     * Publishes the given [event] under its [SiftEvent.routingKey].
     */
    fun publish(event: SiftEvent)
}
