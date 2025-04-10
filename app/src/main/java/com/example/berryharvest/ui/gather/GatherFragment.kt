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
    private lateinit var punnetPriceEditText: EditText

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

//        viewModel.punnetPrice.observe(viewLifecycleOwner) { price ->
//            updatePriceDisplay(price)
//        }
    }

//    private fun setupViews(view: View) {
//        punnetPriceEditText = view.findViewById(R.id.punnetPriceEditText)
//
//        val myButton: Button = view.findViewById(R.id.button)
//        myButton.setOnClickListener { initQRCodeScanner() }
//
//        // Обработчик нажатия кнопки Done на клавиатуре
//        punnetPriceEditText.setOnEditorActionListener { _, actionId, _ ->
//            if (actionId == EditorInfo.IME_ACTION_DONE) {
//                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
//                imm.hideSoftInputFromWindow(punnetPriceEditText.windowToken, 0)
//                handlePriceChange()
//                true
//            } else {
//                false
//            }
//        }
//
//        // Обработчик потери фокуса
//        punnetPriceEditText.setOnFocusChangeListener { _, hasFocus ->
//            if (!hasFocus) {
//                handlePriceChange()
//            }
//        }
//    }

    private fun handlePriceChange() {
        // Отложенная обработка для предотвращения множественных вызовов
        punnetPriceEditText.removeCallbacks(priceChangeRunnable)
        punnetPriceEditText.postDelayed(priceChangeRunnable, 300)
    }

    private val priceChangeRunnable = Runnable {
        val priceText = punnetPriceEditText.text.toString().replace(",", ".")
        val newPrice = priceText.toFloatOrNull()

        if (priceText.isBlank()) {
            updatePriceDisplay(viewModel.punnetPrice.value ?: 0f)
            return@Runnable
        }

        if (newPrice == null) {
            Toast.makeText(activity, "Невірний формат ціни", Toast.LENGTH_SHORT).show()
            updatePriceDisplay(viewModel.punnetPrice.value ?: 0f)
            return@Runnable
        }

        if (newPrice != viewModel.punnetPrice.value) {
            showPriceChangeConfirmationDialog(newPrice)
        }
    }

    private fun showPriceChangeConfirmationDialog(newPrice: Float) {
        AlertDialog.Builder(requireContext())
            .setTitle("Зміна ціни")
            .setMessage("Ви впевнені що бажаєте змінити вартість пінетки?")
            .setPositiveButton("Так") { _, _ ->
                viewModel.updatePunnetPrice(newPrice)
            }
            .setNegativeButton("Ні") { _, _ ->
                updatePriceDisplay(viewModel.punnetPrice.value ?: 0f)
            }
            .show()
    }

    private fun updatePriceDisplay(price: Float) {
        punnetPriceEditText.setText(String.format(Locale.getDefault(), "%.2f", price).replace(".", ","))
    }

    private fun initQRCodeScanner() {
        // Используем новый подход для запуска сканера
        val integrator = IntentIntegrator.forSupportFragment(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setOrientationLocked(true)
            .setPrompt("Проскануйте код")
            .setBeepEnabled(true)

        // Запускаем сканирование через новый лаунчер вместо старого метода
        scannerLauncher.launch(integrator.createScanIntent())
    }

    // Устаревший метод - оставим его для обратной совместимости, но он делегирует
    // обработку результата новому подходу
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        result?.let {
            if (result.contents == null) {
                Toast.makeText(activity, "Сканування припинено", Toast.LENGTH_LONG).show()
            } else {
                viewModel.handleScanResult(result.contents)
            }
        } ?: super.onActivityResult(requestCode, resultCode, data)
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