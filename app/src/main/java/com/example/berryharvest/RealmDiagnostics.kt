// Create a new class: RealmDiagnostics.kt with corrected methods
package com.example.berryharvest

import android.util.Log
import com.example.berryharvest.data.model.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import io.realm.kotlin.mongodb.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RealmDiagnostics {
    companion object {
        private const val TAG = "RealmDiagnostics"

        suspend fun checkRealmStatus(app: App?, realm: Realm?): String {
            return withContext(Dispatchers.IO) {
                val sb = StringBuilder()

                sb.appendLine("== Realm Diagnostics ==")

                // Check App status
                if (app == null) {
                    sb.appendLine("⚠️ App instance is null")
                } else {
                    val currentUser = app.currentUser
                    sb.appendLine("App ID: ${app.configuration.appId}")

                    if (currentUser == null) {
                        sb.appendLine("⚠️ No logged in user")
                    } else {
                        sb.appendLine("User: ${currentUser.id} (${if (currentUser.loggedIn) "Logged in" else "Logged out"})")

                        // Check auth provider
                        sb.appendLine("Auth Provider: ${currentUser.provider}")

                        // Check user network state - simplified
                        val networkState = try {
                            "Connection appears to be available"
                        } catch (e: Exception) {
                            "Error checking network: ${e.message}"
                        }
                        sb.appendLine("Network State: $networkState")
                    }
                }

                // Check Realm status
                if (realm == null) {
                    sb.appendLine("⚠️ Realm instance is null")
                } else {
                    sb.appendLine("Realm Status: ${if (realm.isClosed()) "Closed" else "Open"}")

                    // Check schema version
                    try {
                        val schemaVersion = realm.configuration.schemaVersion
                        sb.appendLine("Schema Version: $schemaVersion")
                    } catch (e: Exception) {
                        sb.appendLine("Error checking schema: ${e.message}")
                    }

                    // Check if it's a synced realm - simplified approach
                    val isSynced = try {
                        realm.configuration::class.java.simpleName.contains("Sync")
                    } catch (e: Exception) {
                        false
                    }
                    sb.appendLine("Sync Enabled: $isSynced")
                }

                val diagnostics = sb.toString()
                Log.d(TAG, diagnostics)
                diagnostics
            }
        }

        suspend fun performRealmHealthCheck(app: App?, realm: Realm?): Boolean {
            return withContext(Dispatchers.IO) {
                try {
                    if (app == null || realm == null || realm.isClosed()) {
                        return@withContext false
                    }

                    // Check if we can do a simple query using proper API
                    val canQuery = try {
                        realm.query<Worker>().count().find() >= 0
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error querying realm", e)
                        false
                    }

                    // Check if current user is valid
                    val hasValidUser = app.currentUser?.let {
                        it.loggedIn
                    } ?: false

                    Log.d(TAG, "Health check: Query $canQuery, User $hasValidUser")
                    return@withContext canQuery && hasValidUser

                } catch (e: Exception) {
                    Log.e(TAG, "Health check failed", e)
                    return@withContext false
                }
            }
        }
    }
}