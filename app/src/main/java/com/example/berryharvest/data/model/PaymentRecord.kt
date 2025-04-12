package com.example.berryharvest.data.model

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class PaymentRecord : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var workerId: String = "" // Reference to Worker._id
    var amount: Float = 0.0f  // Positive for payments made, negative for deductions
    var date: Long = System.currentTimeMillis()
    var notes: String = ""
    var isSynced: Boolean = false
    var isDeleted: Boolean = false
}
