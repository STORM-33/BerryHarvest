package com.example.berryharvest

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.berryharvest.data.repository.ConnectionState
import com.example.berryharvest.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.realm.kotlin.Realm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var progressDialog: AlertDialog? = null
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var realm: Realm? = null
    private lateinit var networkStatusIndicator: TextView
    private lateinit var offlineBanner: TextView
    private var syncSnackbar: Snackbar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        // Set up offline banner using the binding properly
        offlineBanner = binding.appBarMain.offlineBanner

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_scanqr, R.id.nav_assign_for_rows, R.id.nav_workers_registration, R.id.nav_report
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        initializeRealm()
        setupNetworkStatusIndicator()
        setupNetworkSynchronization()
    }

    private fun setupNetworkStatusIndicator() {
        val networkStatusManager = (application as BerryHarvestApplication).networkStatusManager

        // Observe connection state changes
        lifecycleScope.launch {
            networkStatusManager.connectionState.collect { state ->
                updateNetworkStatusUI(state)
            }
        }
    }

    private fun updateNetworkStatusUI(state: ConnectionState) {
        runOnUiThread {
            when (state) {
                is ConnectionState.Connected -> {
                    // Hide offline banner when connected
                    offlineBanner.visibility = View.GONE

                    // Check if we have pending changes to sync
                    val app = application as BerryHarvestApplication
                    if (app.syncManager.hasPendingChanges()) {
                        showSyncNotification()
                    }
                }
                is ConnectionState.Disconnected -> {
                    // Show offline banner when disconnected
                    offlineBanner.visibility = View.VISIBLE
                }
                is ConnectionState.Error -> {
                    // Show offline banner with error state
                    offlineBanner.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showSyncNotification() {
        val snackbar = Snackbar.make(
            binding.root,
            "Несинхронізовані зміни доступні для синхронізації",
            Snackbar.LENGTH_LONG
        )
        snackbar.setAction("Синхронізувати") {
            showSyncingSnackbar()
        }
        snackbar.show()
    }

    private fun initializeRealm() {
        // Show a progress dialog
        showProgressDialog("Connecting to database...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get Realm instance from Application class
                realm = (application as BerryHarvestApplication).getRealmInstance()

                // Run diagnostics
                val diagnostics = RealmDiagnostics.checkRealmStatus(
                    (application as BerryHarvestApplication).app,
                    realm
                )

                Log.d("REALM", diagnostics)

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Log.d("REALM", "Realm initialized successfully")

                    // Display success message
                    Snackbar.make(
                        binding.root,
                        "Database initialized successfully",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("REALM", "Realm initialization error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideProgressDialog()

                    // Show a more informative error message
                    val errorMessage = "Database initialization failed: ${e.message}"

                    // Create a dialog with retry option
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Error")
                        .setMessage("$errorMessage\n\nWould you like to retry or work in offline mode?")
                        .setPositiveButton("Retry") { _, _ -> initializeRealm() }
                        .setNegativeButton("Offline Mode") { _, _ ->
                            // Try to initialize with offline mode
                            initializeOfflineRealm()
                        }
                        .show()
                }
            }
        }
    }

    private fun setupNetworkSynchronization() {
        val app = application as BerryHarvestApplication

        // Use the global sync manager
        lifecycleScope.launch {
            app.networkStatusManager.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    // Show sync indicator if needed
                    if (app.syncManager.hasPendingChanges()) {
                        showSyncingSnackbar()
                    }
                }
            }
        }
    }

    private fun showSyncingSnackbar() {
        // Dismiss any existing snackbar first
        syncSnackbar?.dismiss()

        val syncingMessage = "Синхронізація даних..."
        syncSnackbar = Snackbar.make(binding.root, syncingMessage, Snackbar.LENGTH_INDEFINITE)
            .setAction("Скрити") {
                syncSnackbar?.dismiss()
                syncSnackbar = null
            }

        val app = application as BerryHarvestApplication
        lifecycleScope.launch {
            try {
                val result = app.syncManager.performSync()

                // Make sure to dismiss the current snackbar
                runOnUiThread {
                    syncSnackbar?.dismiss()
                    syncSnackbar = null

                    // Show a new completion snackbar
                    when (result) {
                        is com.example.berryharvest.data.repository.Result.Success -> {
                            Snackbar.make(binding.root, "Синхронізація завершена", Snackbar.LENGTH_SHORT).show()
                        }
                        is com.example.berryharvest.data.repository.Result.Error -> {
                            Snackbar.make(binding.root, "Помилка синхронізації: ${result.message}", Snackbar.LENGTH_LONG).show()
                        }
                        is com.example.berryharvest.data.repository.Result.Loading -> {
                            // This should not happen
                        }
                    }
                }
            } catch (e: Exception) {
                // Ensure snackbar is dismissed even if an error occurs
                runOnUiThread {
                    syncSnackbar?.dismiss()
                    syncSnackbar = null
                    Snackbar.make(binding.root, "Помилка синхронізації: ${e.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        syncSnackbar?.show()
    }

    private fun showProgressDialog(message: String) {
        if (progressDialog == null) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
            val messageTextView = dialogView.findViewById<TextView>(R.id.progressMessageTextView)
            messageTextView.text = message

            progressDialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
        }

        progressDialog?.show()
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
    }

    private fun initializeOfflineRealm() {
        showProgressDialog("Setting up offline mode...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Force offline mode in application
                (application as BerryHarvestApplication).forceOfflineMode = true

                // Try to get offline Realm instance
                realm = (application as BerryHarvestApplication).getRealmInstance()

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Snackbar.make(
                        binding.root,
                        "Working in offline mode. Changes will sync when connection is restored.",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("REALM", "Offline Realm initialization error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    showError("Failed to initialize offline database: ${e.message}")
                }
            }
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)

        // Add sync button
        menu.add(Menu.NONE, R.id.action_sync, Menu.NONE, "Синхронізувати")
            .setIcon(R.drawable.ic_sync)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync -> {
                if ((application as BerryHarvestApplication).networkStatusManager.isNetworkAvailable()) {
                    showSyncingSnackbar()
                } else {
                    Snackbar.make(binding.root, "Немає мережевого з'єднання", Snackbar.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_settings -> {
                // Handle settings
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        for (fragment in supportFragmentManager.fragments) {
            fragment.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun getRealm(): Realm? {
        return realm
    }

    override fun onDestroy() {
        syncSnackbar?.dismiss()
        syncSnackbar = null
        super.onDestroy()
    }
}

