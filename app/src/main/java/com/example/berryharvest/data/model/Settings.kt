package com.example.berryharvest.data.model

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

class Settings : RealmObject {
    @PrimaryKey
    var _id: String = UUID.randomUUID().toString()
    var punnetPrice: Float = 0.0f
    var isSynced: Boolean = false
}