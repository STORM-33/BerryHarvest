package com.example.berryharvest.data.repository

/**
 * Represents an operation that's pending synchronization with the server.
 */
sealed class PendingOperation {
    abstract val entityId: String
    abstract val entityType: String

    /**
     * Add operation that's pending sync.
     */
    data class Add(
        override val entityId: String,
        override val entityType: String
    ) : PendingOperation()

    /**
     * Update operation that's pending sync.
     */
    data class Update(
        override val entityId: String,
        override val entityType: String
    ) : PendingOperation()

    /**
     * Delete operation that's pending sync.
     */
    data class Delete(
        override val entityId: String,
        override val entityType: String
    ) : PendingOperation()
}