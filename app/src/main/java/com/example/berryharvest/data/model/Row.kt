package com.example.berryharvest.data.model

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Row : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var rowNumber: Int = 0
    var quarter: Int = 1 // 1-4 for quarters
    var berryVariety: String = "" // Type of berry in this row
    var isCollected: Boolean = false
    var createdAt: Long = System.currentTimeMillis()
    var collectedAt: Long? = null // When it was collected
    var lastModifiedAt: Long = System.currentTimeMillis() // When collection status last changed
    var isSynced: Boolean = false
    var isDeleted: Boolean = false
}