package com.example.berryharvest.ui.add_worker

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Worker : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var fullName: String = ""
    var phoneNumber: String = ""
    var isSynced: Boolean = false
}
