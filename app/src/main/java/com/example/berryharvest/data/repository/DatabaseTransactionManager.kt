package com.example.berryharvest.data.repository

import com.example.berryharvest.BerryHarvestApplication
import io.realm.kotlin.MutableRealm
import io.realm.kotlin.Realm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages database transactions consistently across the application.
 * This class encapsulates all direct Realm access.
 */
class DatabaseTransactionManager(private val application: BerryHarvestApplication) {

    /**
     * Executes a write transaction safely.
     *
     * @param block The transaction code to execute
     * @return The result of the transaction
     */
    suspend fun <T> executeTransaction(block: MutableRealm.() -> T): T {
        return application.safeWriteTransaction(block)
    }

    /**
     * Executes a query operation safely.
     *
     * @param block The query code to execute
     * @return The result of the query
     */
    suspend fun <T> executeQuery(block: suspend (Realm) -> T): T {
        return withContext(Dispatchers.IO) {
            val realm = application.getRealmInstance()
            block(realm)
        }
    }
}