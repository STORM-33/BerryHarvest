package com.example.berryharvest.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.core.widget.PopupWindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.berryharvest.R

class SearchableSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var searchEditText: EditText
    private var adapter: SearchableSpinnerAdapter<*>? = null
    private var popupWindow: PopupWindow? = null
    private var onItemSelectedListener: ((SearchableItem) -> Unit)? = null

    // State management
    private var isItemSelected = false
    private var selectedItemText: String? = null
    private var isPopupShowing = false

    companion object {
        private const val TAG = "SearchableSpinnerView"
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_searchable_spinner, this, true)
        searchEditText = findViewById(R.id.searchEditText)
        setupViews()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        // Set up text watcher for filtering
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val text = s.toString()

                // If the text matches selected item, don't filter
                if (isItemSelected && text == selectedItemText) {
                    return
                }

                // If text changed from selected item, reset selection
                if (isItemSelected && text != selectedItemText) {
                    isItemSelected = false
                    selectedItemText = null
                }

                // Filter and show dropdown
                adapter?.filter(text)
                if (!isItemSelected && searchEditText.hasFocus()) {
                    showDropdown()
                }
            }
        })

        // Handle focus changes
        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // If gaining focus while item is selected, clear immediately
                if (isItemSelected) {
                    clearSelectionInternal()
                    showDropdown()
                    showInputMethod()
                } else if (!isPopupShowing) {
                    showDropdown()
                    showInputMethod()
                }
            } else {
                // Delay hiding to allow click events on dropdown items
                postDelayed({ hideDropdown() }, 200)
            }
        }

        // Handle clicks on EditText (for when already focused)
        searchEditText.setOnClickListener {
            if (isItemSelected) {
                // Clear selection and show dropdown
                clearSelectionInternal()
                showDropdown()
                showInputMethod()
            } else if (!isPopupShowing) {
                showDropdown()
                showInputMethod()
            }
        }

        // Handle touch events to clear on first touch when selected
        searchEditText.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (isItemSelected && !searchEditText.hasFocus()) {
                    // Clear immediately on touch, before focus is gained
                    clearSelectionInternal()
                    searchEditText.requestFocus()
                    showDropdown()
                    showInputMethod()
                    true // Consume the event
                } else {
                    false // Let the event propagate normally
                }
            } else {
                false
            }
        }

        // Handle IME actions
        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideInputMethod()
                searchEditText.clearFocus()
                true
            } else {
                false
            }
        }

        // Handle back key
        searchEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (isPopupShowing) {
                    hideDropdown()
                    true
                } else {
                    searchEditText.clearFocus()
                    hideInputMethod()
                    false
                }
            } else {
                false
            }
        }

        // Make the entire view clickable
        setOnClickListener {
            if (isItemSelected) {
                // Clear immediately if item is selected
                clearSelectionInternal()
                searchEditText.requestFocus()
                showDropdown()
                showInputMethod()
            } else {
                searchEditText.requestFocus()
                showInputMethod()
            }
        }
    }

    private fun showInputMethod() {
        searchEditText.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideInputMethod() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    private fun showDropdown() {
        if (adapter == null || adapter?.itemCount == 0) return

        // Create popup if needed
        if (popupWindow == null) {
            createPopupWindow()
        }

        // Update popup content
        popupWindow?.contentView?.findViewById<RecyclerView>(R.id.popupRecyclerView)?.adapter = adapter

        if (popupWindow?.isShowing == false) {
            try {
                // Calculate position to avoid keyboard
                val location = IntArray(2)
                getLocationOnScreen(location)

                // Get visible display frame to account for keyboard
                val displayFrame = Rect()
                searchEditText.getWindowVisibleDisplayFrame(displayFrame)

                // Calculate available space above and below
                val anchorTop = location[1] + height
                val screenHeight = displayFrame.bottom
                val spaceBelow = screenHeight - anchorTop
                val spaceAbove = location[1] - displayFrame.top

                // Determine maximum height for popup
                val maxPopupHeight = if (spaceBelow > spaceAbove) {
                    // Show below if more space
                    (spaceBelow * 0.8).toInt() // Use 80% of available space
                } else {
                    // Show above if more space
                    (spaceAbove * 0.8).toInt()
                }

                // Set popup dimensions
                popupWindow?.apply {
                    width = this@SearchableSpinnerView.width
                    height = ViewGroup.LayoutParams.WRAP_CONTENT

                    // Set max height constraint
                    contentView?.findViewById<RecyclerView>(R.id.popupRecyclerView)?.apply {
                        layoutParams = layoutParams.apply {
                            height = maxPopupHeight.coerceAtMost(
                                (300 * context.resources.displayMetrics.density).toInt() // Max 300dp
                            )
                        }
                    }

                    // Show popup
                    if (spaceBelow > spaceAbove || spaceAbove < 100) {
                        // Show below
                        showAsDropDown(this@SearchableSpinnerView, 0, 0)
                    } else {
                        // Show above
                        showAsDropDown(
                            this@SearchableSpinnerView,
                            0,
                            -(height + this@SearchableSpinnerView.height)
                        )
                    }

                    isPopupShowing = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error showing dropdown", e)
            }
        }
    }

    private fun createPopupWindow() {
        // Create popup content view
        val popupView = LayoutInflater.from(context).inflate(
            R.layout.searchable_spinner_popup,
            null
        )

        val recyclerView = popupView.findViewById<RecyclerView>(R.id.popupRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        // Create popup window
        popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // Not focusable to keep keyboard open
        ).apply {
            // Set properties
            isOutsideTouchable = true
            setBackgroundDrawable(context.getDrawable(R.drawable.dropdown_background))
            elevation = 8f

            // Important: Set soft input mode to adjust resize
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

            // Set dismiss listener
            setOnDismissListener {
                isPopupShowing = false
            }
        }
    }

    private fun hideDropdown() {
        popupWindow?.dismiss()
        isPopupShowing = false
    }

    fun <T : SearchableItem> setAdapter(items: List<T>) {
        Log.d(TAG, "Setting adapter with ${items.size} items")

        adapter = SearchableSpinnerAdapter(items) { selectedItem ->
            Log.d(TAG, "Item selected: ${selectedItem.getDisplayText()}")

            // Update state
            isItemSelected = true
            selectedItemText = selectedItem.getDisplayText()

            // Set text without triggering filter
            searchEditText.removeTextChangedListener(textWatcher)
            searchEditText.setText(selectedItemText)
            searchEditText.setSelection(searchEditText.text.length)
            searchEditText.addTextChangedListener(textWatcher)

            // Hide dropdown and keyboard
            hideDropdown()
            hideInputMethod()
            searchEditText.clearFocus()

            // Notify listener
            onItemSelectedListener?.invoke(selectedItem)
        }

        // Update popup if it exists
        popupWindow?.contentView?.findViewById<RecyclerView>(R.id.popupRecyclerView)?.adapter = adapter
    }

    fun setOnItemSelectedListener(listener: (SearchableItem) -> Unit) {
        onItemSelectedListener = listener
    }

    fun clearSelection() {
        Log.d(TAG, "Clearing selection")
        clearSelectionInternal()

        // Ensure EditText is in correct state
        searchEditText.post {
            searchEditText.isFocusable = true
            searchEditText.isFocusableInTouchMode = true
            searchEditText.isEnabled = true
        }
    }

    private fun clearSelectionInternal() {
        isItemSelected = false
        selectedItemText = null
        searchEditText.setText("")
        adapter?.filter("")
    }

    fun hideDropdownForced() {
        hideDropdown()
        hideInputMethod()
        searchEditText.clearFocus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideDropdown()
        popupWindow = null
    }

    // Store text watcher reference for adding/removing
    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            val text = s.toString()

            // If the text matches selected item, don't filter
            if (isItemSelected && text == selectedItemText) {
                return
            }

            // If text changed from selected item, reset selection
            if (isItemSelected && text != selectedItemText) {
                isItemSelected = false
                selectedItemText = null
            }

            // Filter and show dropdown
            adapter?.filter(text)
            if (!isItemSelected && searchEditText.hasFocus()) {
                showDropdown()
            }
        }
    }
}