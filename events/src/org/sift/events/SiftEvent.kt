package org.sift.events

/**
 * Marker contract for all events published by sift components.
 */
interface SiftEvent {
    /**
     * The routing key under which this event is published.
     */
    val routingKey: String
}
