package com.example.berryharvest.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R

class SearchableSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var searchEditText: EditText
    private var resultsRecyclerView: RecyclerView
    private var dropdownContainer: LinearLayout
    private var adapter: SearchableSpinnerAdapter<*>? = null
    private var isSelectionMode = false

    private var onItemSelectedListener: ((SearchableItem) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_searchable_spinner, this, true)

        searchEditText = findViewById(R.id.searchEditText)
        resultsRecyclerView = findViewById(R.id.resultsRecyclerView)
        dropdownContainer = findViewById(R.id.dropdownContainer)

        // Ensure these are explicitly set during initialization
        isSelectionMode = false
        searchEditText.isFocusable = true
        searchEditText.isFocusableInTouchMode = true

        setupViews()
        setupBackHandler()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Reset selection mode when view is reattached
        isSelectionMode = false
        searchEditText.isFocusable = true
        searchEditText.isFocusableInTouchMode = true
    }

    private fun setupViews() {
        resultsRecyclerView.layoutManager = LinearLayoutManager(context)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isSelectionMode) {
                    adapter?.filter(s.toString())
                    showDropdown()
                }
            }
        })

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideDropdown()
            } else if (!isSelectionMode) {
                showDropdown()
            }
        }

        searchEditText.setOnClickListener {
            if (isSelectionMode) {
                isSelectionMode = false
                searchEditText.text.clear()
                showDropdown()
            }
        }

        setOnClickListener {
            if (isSelectionMode) {
                isSelectionMode = false
                searchEditText.text.clear()
                showDropdown()
            } else {
                searchEditText.requestFocus()
                showInputMethod()
            }
        }
    }

    private fun setupBackHandler() {
        searchEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                searchEditText.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                hideDropdown()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun showInputMethod() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showDropdown() {
        dropdownContainer.visibility = View.VISIBLE
    }

    private fun hideDropdown() {
        dropdownContainer.visibility = View.GONE
    }

    fun <T : SearchableItem> setAdapter(items: List<T>) {
        adapter = SearchableSpinnerAdapter(items) { selectedItem ->
            onItemSelectedListener?.invoke(selectedItem)
            searchEditText.setText(selectedItem.getDisplayText())
            isSelectionMode = true
            searchEditText.isFocusable = false
            searchEditText.isFocusableInTouchMode = false
            hideDropdown()
        }
        resultsRecyclerView.adapter = adapter
    }

    fun setOnItemSelectedListener(listener: (SearchableItem) -> Unit) {
        onItemSelectedListener = listener
    }

    public fun hideDropdownForced() {
        dropdownContainer.visibility = View.GONE
        isSelectionMode = false
    }

    @SuppressLint("SetTextI18n")
    fun clearSelection() {
        searchEditText.setText("")
        isSelectionMode = false
        searchEditText.isFocusable = true
        searchEditText.isFocusableInTouchMode = true

        // Additional reset steps
        searchEditText.clearFocus()
        hideDropdown()

        // Force UI update
        searchEditText.post {
            searchEditText.invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideDropdown()
        clearSelection()
    }
}