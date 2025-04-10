package com.example.berryharvest.data.model

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Worker : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var sequenceNumber: Int = 0
    var fullName: String = ""
    var phoneNumber: String = ""
    var qrCode: String = ""
    var createdAt: Long = System.currentTimeMillis()
    var isSynced: Boolean = false
    var isDeleted: Boolean = false

}