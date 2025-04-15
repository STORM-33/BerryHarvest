package com.example.berryharvest

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import java.util.Locale

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

        lifecycleScope.launch(Dispatchers.IO) {
            (application as BerryHarvestApplication).ensureSubscriptions()
        }

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

        // Use repositories instead of direct Realm access
        lifecycleScope.launch {
            try {
                // Wait for repository provider to be ready
                val provider = (application as BerryHarvestApplication).repositoryProvider

                // Try a simple operation to verify connection
                val settings = provider.settingsRepository.getSettings()

                Log.d("REALM", "Repositories initialized successfully")

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                }
            } catch (e: Exception) {
                Log.e("REALM", "Repository initialization error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideProgressDialog()

                    // Show a more informative error message
                    val errorMessage = "Database initialization failed: ${e.message}"

                    // Create a dialog with retry option
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Connection Error")
                        .setMessage("$errorMessage\n\nБажаєте спробувати ще раз чи продовжити в оффлайн режимі?")
                        .setPositiveButton("повторити") { _, _ -> initializeRealm() }
                        .setNegativeButton("оффлайн") { _, _ ->
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

        lifecycleScope.launch {
            try {
                // Force offline mode in application
                (application as BerryHarvestApplication).forceOfflineMode = true

                // Try a simple operation to verify connection
                val provider = (application as BerryHarvestApplication).repositoryProvider
                val settings = provider.settingsRepository.getSettings()

                withContext(Dispatchers.Main) {
                    hideProgressDialog()
                    Snackbar.make(
                        binding.root,
                        "Working in offline mode. Changes will sync when connection is restored.",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("REALM", "Offline Repository initialization error: ${e.message}", e)
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
            R.id.action_new_workday -> {
                startNewWorkday()
                true
            }
            R.id.action_settings -> {
                // Handle settings
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun startNewWorkday() {
        AlertDialog.Builder(this)
            .setTitle("Новий робочий день")
            .setMessage("Ви дійсно бажаєте розпочати новий робочий день?")
            .setPositiveButton("Так") { _, _ ->
                showSaveAssignmentsDialog()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun showSaveAssignmentsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Збереження призначень")
            .setMessage("Зберегти призначення на рядки?")
            .setPositiveButton("Так") { _, _ ->
                // If user wants to save assignments, go directly to punnet price dialog
                showPunnetPriceUpdateDialog()
            }
            .setNegativeButton("Ні") { _, _ ->
                // If user doesn't want to save, ask if they want to delete assignments
                showDeleteAssignmentsConfirmation()
            }
            .show()
    }

    private fun showDeleteAssignmentsConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Видалення призначень")
            .setMessage("Бажаєте видалити всі поточні призначення на рядки?")
            .setPositiveButton("Так") { _, _ ->
                // User confirmed deletion
                deleteAllAssignments()
            }
            .setNegativeButton("Ні") { _, _ ->
                // Skip deletion and proceed to next step
                showPunnetPriceUpdateDialog()
            }
            .show()
    }

    private fun deleteAllAssignments() {
        // Show progress while deleting
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Видалення")
            .setMessage("Видалення всіх призначень...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            try {
                val assignmentRepository = (application as BerryHarvestApplication)
                    .repositoryProvider.assignmentRepository

                val rowNumbers = assignmentRepository.getAllRowNumbers()
                Log.d("MainActivity", "Found ${rowNumbers.size} rows to delete")

                // Delete each row's assignments
                var success = true
                for (rowNumber in rowNumbers) {
                    Log.d("MainActivity", "Deleting row $rowNumber")
                    val result = assignmentRepository.deleteByRow(rowNumber)
                    if (result !is com.example.berryharvest.data.repository.Result.Success) {
                        success = false
                        Log.e("MainActivity", "Failed to delete row $rowNumber: $result")
                    } else {
                        Log.d("MainActivity", "Successfully deleted row $rowNumber")
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()

                    if (success) {
                        Toast.makeText(this@MainActivity,
                            "Всі призначення видалено", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity,
                            "Виникли помилки при видаленні призначень", Toast.LENGTH_SHORT).show()
                    }

                    // Proceed to next step
                    showPunnetPriceUpdateDialog()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error deleting assignments", e)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity,
                        "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()

                    // Still proceed to next step
                    showPunnetPriceUpdateDialog()
                }
            }
        }
    }


    private fun showPunnetPriceUpdateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_price, null)
        val priceEditText = dialogView.findViewById<EditText>(R.id.priceEditText)

        lifecycleScope.launch {
            try {
                val settingsRepository = (application as BerryHarvestApplication).repositoryProvider.settingsRepository
                val currentPrice = settingsRepository.getPunnetPrice()

                withContext(Dispatchers.Main) {
                    priceEditText.setText(String.format(Locale.getDefault(), "%.2f", currentPrice))

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ціна пінетки")
                        .setMessage("Встановіть ціну пінетки на новий робочий день:")
                        .setView(dialogView)
                        .setPositiveButton("Зберегти") { _, _ ->
                            val priceText = priceEditText.text.toString().replace(",", ".")
                            val newPrice = priceText.toFloatOrNull()

                            if (newPrice == null) {
                                Toast.makeText(this@MainActivity, "Невірний формат ціни", Toast.LENGTH_SHORT).show()
                                return@setPositiveButton
                            }

                            if (newPrice != currentPrice) {
                                showPriceConfirmationDialog(newPrice)
                            } else {
                                showWorkdaySuccessDialog()
                            }
                        }
                        .setNegativeButton("Залишити поточну") { _, _ ->
                            showWorkdaySuccessDialog()
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Помилка отримання ціни: ${e.message}", Toast.LENGTH_SHORT).show()
                    showWorkdaySuccessDialog()
                }
            }
        }
    }

    private fun showPriceConfirmationDialog(newPrice: Float) {
        AlertDialog.Builder(this)
            .setTitle("Підтвердження зміни ціни")
            .setMessage("Ви впевнені, що хочете змінити ціну пінетки на ${String.format(Locale.getDefault(), "%.2f", newPrice)}₴?")
            .setPositiveButton("Так") { _, _ ->
                // Update price in repository
                lifecycleScope.launch {
                    try {
                        val settingsRepository = (application as BerryHarvestApplication).repositoryProvider.settingsRepository
                        settingsRepository.updatePunnetPrice(newPrice)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Ціну оновлено", Toast.LENGTH_SHORT).show()
                            showWorkdaySuccessDialog()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Помилка оновлення ціни: ${e.message}", Toast.LENGTH_SHORT).show()
                            showWorkdaySuccessDialog()
                        }
                    }
                }
            }
            .setNegativeButton("Ні") { _, _ ->
                showWorkdaySuccessDialog()
            }
            .show()
    }

    private fun showWorkdaySuccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Успіх")
            .setMessage("Новий робочий день розпочато!")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        for (fragment in supportFragmentManager.fragments) {
            fragment.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        syncSnackbar?.dismiss()
        syncSnackbar = null
        super.onDestroy()
    }
}

