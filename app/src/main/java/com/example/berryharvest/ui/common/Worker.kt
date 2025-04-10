package com.example.berryharvest.ui.common

import com.example.berryharvest.data.model.Worker

fun Worker.toSearchableItem(): WorkerSearchableItem {
    return WorkerSearchableItem(this)
}

data class WorkerSearchableItem(
    val worker: Worker
) : SearchableItem {
    override fun getDisplayText(): String = "${worker.fullName} (${worker.sequenceNumber})"
    override fun getSearchableText(): String = "${worker.fullName} ${worker.sequenceNumber}"
}