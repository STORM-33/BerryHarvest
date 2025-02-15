package com.example.berryharvest.ui.assign_rows

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Assignment : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var rowNumber: Int = 0
    var workerId: String = "" // Reference to Worker._id
    var isSynced: Boolean = false
    var isDeleted: Boolean = false
}
