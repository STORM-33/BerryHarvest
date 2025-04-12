package com.example.berryharvest.ui.gather

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.berryharvest.R
import com.example.berryharvest.data.model.Worker
import com.google.zxing.integration.android.IntentIntegrator
import java.util.Locale

class GatherFragment : Fragment() {
    private lateinit var viewModel: GatherViewModel
    private lateinit var punnetPriceTextView: TextView

    // Создаем новый способ обработки результатов сканирования
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val scanResult = IntentIntegrator.parseActivityResult(
            IntentIntegrator.REQUEST_CODE, result.resultCode, result.data
        )

        scanResult?.let {
            if (scanResult.contents == null) {
                Toast.makeText(activity, "Сканування припинено", Toast.LENGTH_LONG).show()
            } else {
                viewModel.handleScanResult(scanResult.contents)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_gather, container, false)
        viewModel = ViewModelProvider(this).get(GatherViewModel::class.java)
        punnetPriceTextView = view.findViewById(R.id.punnetPriceTextView)

        setupPriceTextView()

        //setupViews(view)
        setupObservers()

        return view
    }

    private fun setupObservers() {
        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                viewModel.clearErrorMessage()
            }
        }

        viewModel.workerAssignment.observe(viewLifecycleOwner) { pair ->
            pair?.let { (worker, rowNumber) ->
                showGatherConfirmationDialog(worker, rowNumber)
                viewModel.clearWorkerAssignment()
            }
        }

        viewModel.successMessage.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(activity, "Данні збережено", Toast.LENGTH_SHORT).show()
                viewModel.clearSuccessMessage()
            }
        }

        viewModel.punnetPrice.observe(viewLifecycleOwner) { price ->
            updatePriceDisplay(price)
        }
    }

    private fun setupPriceTextView() {
        punnetPriceTextView.setOnLongClickListener {
            showPriceChangeMenu()
            true
        }
    }

    private fun showPriceChangeMenu() {
        AlertDialog.Builder(requireContext())
            .setTitle("Зміна ціни")
            .setMessage("Бажаєте змінити вартість пінетки?")
            .setPositiveButton("Так") { _, _ ->
                showPriceEditDialog()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun showPriceEditDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_price, null)
        val priceEditText = dialogView.findViewById<EditText>(R.id.priceEditText)

        // Set current price
        val currentPrice = viewModel.punnetPrice.value ?: 0f
        priceEditText.setText(String.format(Locale.getDefault(), "%.2f", currentPrice))

        AlertDialog.Builder(requireContext())
            .setTitle("Введіть нову ціну")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val priceText = priceEditText.text.toString().replace(",", ".")
                val newPrice = priceText.toFloatOrNull()

                if (newPrice == null) {
                    Toast.makeText(requireContext(), "Невірний формат ціни", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (newPrice != currentPrice) {
                    showPriceConfirmationDialog(newPrice)
                }
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun showPriceConfirmationDialog(newPrice: Float) {
        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження")
            .setMessage("Ви впевнені, що хочете змінити ціну на ${String.format(Locale.getDefault(), "%.2f", newPrice)}₴?")
            .setPositiveButton("Так") { _, _ ->
                viewModel.updatePunnetPrice(newPrice)
                Toast.makeText(requireContext(), "Ціну оновлено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Ні", null)
            .show()
    }

    private fun updatePriceDisplay(price: Float) {
        punnetPriceTextView.text = String.format(Locale.getDefault(), "%.2f", price)
    }

    @SuppressLint("SetTextI18n")
    private fun showGatherConfirmationDialog(worker: Worker, rowNumber: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gather_confirmation, null)
        val workerNameTextView: TextView = dialogView.findViewById(R.id.workerNameTextView)
        val rowNumberTextView: TextView = dialogView.findViewById(R.id.rowNumberTextView)
        val punnetsEditText: EditText = dialogView.findViewById(R.id.punnetsEditText)
        val priceTextView: TextView = dialogView.findViewById(R.id.priceTextView)

        // Используем единый формат отображения цены с запятой
        val formattedPrice = String.format(Locale.getDefault(), "%.2f", viewModel.punnetPrice.value ?: 0f).replace(".", ",")

        workerNameTextView.text = "${worker.fullName} [${worker.sequenceNumber}]"
        rowNumberTextView.text = "Ряд: $rowNumber"
        punnetsEditText.setText("10")
        priceTextView.text = "Ціна за пінетку: $formattedPrice"

        AlertDialog.Builder(requireContext())
            .setTitle("Підтвердження")
            .setView(dialogView)
            .setPositiveButton("Зберегти") { _, _ ->
                val numOfPunnetsText = punnetsEditText.text.toString()
                val numOfPunnets = numOfPunnetsText.toIntOrNull()

                when {
                    numOfPunnetsText.isBlank() -> {
                        Toast.makeText(activity, "Введіть кількість пінеток", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets == null -> {
                        Toast.makeText(activity, "Невірний формат кількості", Toast.LENGTH_SHORT).show()
                    }
                    numOfPunnets <= 0 -> {
                        Toast.makeText(activity, "Кількість пінеток повинна бути більше 0", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        viewModel.saveGatherData(worker._id, rowNumber, numOfPunnets)
                    }
                }
            }
            .setNegativeButton("Відміна", null)
            .show()
    }
}