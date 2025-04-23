package com.example.berryharvest.ui.common

import com.example.berryharvest.data.model.Worker

fun Worker.toSearchableItem(): WorkerSearchableItem {
    return WorkerSearchableItem(this)
}

data class WorkerSearchableItem(
    val worker: Worker
) : SearchableItem {
    override fun getDisplayText(): String = "[${worker.sequenceNumber}] ${worker.fullName}"
    override fun getSearchableText(): String =  "${worker.sequenceNumber} ${worker.fullName}"
}