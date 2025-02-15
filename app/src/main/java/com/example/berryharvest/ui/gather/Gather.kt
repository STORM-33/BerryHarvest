package com.example.berryharvest.ui.gather

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Gather : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var workerId: String? = null
    var rowNumber: Int? = null
    var numOfPunnets: Int? = null
    var dateTime: String? = null
    var isSynced: Boolean? = null
    var isDeleted: Boolean? = null
}
