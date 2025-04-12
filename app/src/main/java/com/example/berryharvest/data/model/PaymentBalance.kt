package com.example.berryharvest.data.model

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class PaymentBalance : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var workerId: String = "" // Reference to Worker._id
    var currentBalance: Float = 0.0f
    var lastUpdated: Long = System.currentTimeMillis()
    var isSynced: Boolean = false
}
