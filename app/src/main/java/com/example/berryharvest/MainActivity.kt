package com.example.berryharvest

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
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
    private lateinit var networkManager: NetworkConnectivityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkManager = NetworkConnectivityManager(applicationContext)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_content_main)

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.nav_scanqr, R.id.nav_assign_for_rows, R.id.nav_workers_registration, R.id.nav_report
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        initializeRealm()
        networkManager = NetworkConnectivityManager(applicationContext)
        setupNetworkSynchronization()
    }

    private fun initializeRealm() {
        // Check network availability before initializing Realm
        if (!networkManager.isNetworkAvailable()) {
            showNetworkError()
            // Register callback for automatic initialization when network becomes available
            networkManager.registerNetworkCallback { isAvailable ->
                if (isAvailable) {
                    initializeRealmWithNetwork()
                }
            }
            return
        }

        initializeRealmWithNetwork()
    }

    private fun initializeRealmWithNetwork() {
        // Show a progress dialog instead of relying on a view that doesn't exist
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
                        .setPositiveButton("Retry") { _, _ -> initializeRealmWithNetwork() }
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
        // Get repositories
        val app = application as BerryHarvestApplication
        val workerRepo = app.repositoryProvider.workerRepository
        val assignmentRepo = app.repositoryProvider.assignmentRepository

        // Create enhanced network manager if not already created
        val networkManager = app.repositoryProvider.networkManager

        // Start observing network changes and sync when needed
        lifecycleScope.launch {
            networkManager.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    Log.d("MainActivity", "Network connected, initiating sync")
                    try {
                        workerRepo.syncPendingChanges()
                        assignmentRepo.syncPendingChanges()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error syncing data", e)
                    }
                }
            }
        }

        // Start repositories' own sync mechanisms
        workerRepo.startSyncWhenOnline(lifecycleScope, networkManager)
        assignmentRepo.startSyncWhenOnline(lifecycleScope, networkManager)
    }

    // Helper method to update UI based on connection state
    private fun updateNetworkStatusUI(state: ConnectionState) {
        runOnUiThread {
            // Example: Update a status TextView or indicator
            // networkStatusTextView.text = when(state) {
            //     is ConnectionState.Connected -> "Online"
            //     is ConnectionState.Disconnected -> "Offline"
            //     is ConnectionState.Error -> "Error: ${state.message}"
            // }
        }
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

    private fun showNetworkError() {
        Snackbar.make(
            binding.root,
            "Немає з’єднання. Деякі функції можуть бути недоступні",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.add("Test Database").apply {
            setOnMenuItemClickListener {
                true
            }
        }
        return true
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
        realm?.close()
        super.onDestroy()
    }

}

