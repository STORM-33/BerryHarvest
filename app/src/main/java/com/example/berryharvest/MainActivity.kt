package com.example.berryharvest

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.berryharvest.databinding.ActivityMainBinding
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.realm.kotlin.Realm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private var realm: Realm? = null
    private lateinit var networkManager: NetworkConnectivityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkManager = NetworkConnectivityManager(applicationContext)

        // Настройка UI компонентов
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

        // Инициализация Realm в фоновом потоке с использованием lifecycleScope
        initializeRealm()
    }

    private fun initializeRealm() {
        // Проверка доступности сети перед инициализацией Realm
        if (!networkManager.isNetworkAvailable()) {
            showNetworkError()
            // Регистрируем callback для автоматической инициализации при появлении сети
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Получаем инстанс Realm из Application класса
                realm = (application as MyApplication).getRealmInstance()

                withContext(Dispatchers.Main) {
                    Log.d("REALM", "Realm initialized successfully")
                    // Можно выполнить действия, зависящие от Realm, например:
                    // updateUIWithRealmData()
                }
            } catch (e: Exception) {
                Log.e("REALM", "Realm initialization error: ${e.message}")
                withContext(Dispatchers.Main) {
                    showError("Ошибка инициализации базы данных: ${e.message}")
                }
            }
        }
    }

    private fun showNetworkError() {
        Snackbar.make(
            binding.root,
            "Нет подключения к сети. Некоторые функции могут быть недоступны.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
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

    // Метод для получения инстанса Realm, который могут использовать фрагменты
    fun getRealm(): Realm? {
        return realm
    }

    override fun onDestroy() {
        // Закрываем Realm при уничтожении Activity
        realm?.close()
        super.onDestroy()
    }
}