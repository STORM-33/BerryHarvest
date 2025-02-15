package com.example.berryharvest.ui.gather

import android.content.Intent
import android.icu.text.SimpleDateFormat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.berryharvest.MyApplication
import com.google.zxing.integration.android.IntentIntegrator
import com.example.berryharvest.R
import com.example.berryharvest.ui.add_worker.Worker
import io.realm.kotlin.Realm
import io.realm.kotlin.ext.query
import java.util.Date
import java.util.Locale
import java.util.UUID

class GatherFragment : Fragment() {
    private lateinit var realm: Realm

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_gather, container, false)

        realm = (requireActivity().application as MyApplication).getRealmInstance()

        val myButton: Button = view.findViewById(R.id.button)
        myButton.setOnClickListener {
            initQRCodeScanner()
        }

        return view
    }

    private fun initQRCodeScanner() {
        val integrator = IntentIntegrator.forSupportFragment(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setOrientationLocked(true)
        integrator.setPrompt("Проскануйте код")
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                Toast.makeText(activity, "Сканування припинено", Toast.LENGTH_LONG).show()
            } else {
                // Обработка результата сканирования
                handleScanResult(result.contents)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleScanResult(workerId: String) {
        // Получаем информацию о работнике
        val worker = realm.query<Worker>("_id == $0", workerId).first().find()

        if (worker != null) {
            showGatherConfirmationDialog(worker)
        } else {
            Toast.makeText(activity, "Працівника не знайдено", Toast.LENGTH_LONG).show()
        }
    }

    private fun showGatherConfirmationDialog(worker: Worker) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gather_confirmation, null)
        val workerNameTextView: TextView = dialogView.findViewById(R.id.workerNameTextView)
        // val rowNumberTextView: TextView = dialogView.findViewById(R.id.rowNumberTextView)
        val punnetsEditText: EditText = dialogView.findViewById(R.id.punnetsEditText)

        workerNameTextView.text = "Имя: ${worker.fullName}"
        //rowNumberTextView.text = "Ряд: ${worker.rowNumber}"
        punnetsEditText.setText("10")

        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val numOfPunnets = punnetsEditText.text.toString().toIntOrNull() ?: 10
                saveGatherData(worker._id, numOfPunnets) // worker.rowNumber,
            }
            .setNegativeButton("Відміна", null)
            .show()
    }

    private fun saveGatherData(workerId: String, numOfPunnets: Int) { //rowNumber: Int,
        realm.writeBlocking {
            copyToRealm(Gather().apply {
                _id = UUID.randomUUID().toString()
                this.workerId = workerId
                // this.rowNumber = rowNumber
                this.numOfPunnets = numOfPunnets
                dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                isSynced = false
                isDeleted = false
            })
        }
        Toast.makeText(activity, "Данні збережено", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        realm.close()
    }
}