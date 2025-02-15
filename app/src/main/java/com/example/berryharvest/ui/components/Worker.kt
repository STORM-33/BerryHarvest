package com.example.berryharvest.ui.components

import com.example.berryharvest.ui.add_worker.Worker

fun Worker.toSearchableItem(): WorkerSearchableItem {
    return WorkerSearchableItem(this)
}

data class WorkerSearchableItem(
    val worker: Worker
) : SearchableItem {
    override fun getDisplayText(): String = "${worker.fullName} (${worker.sequenceNumber})"
    override fun getSearchableText(): String = "${worker.fullName} ${worker.sequenceNumber}"
}