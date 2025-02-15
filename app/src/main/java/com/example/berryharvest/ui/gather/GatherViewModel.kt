package com.example.berryharvest.ui.gather

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class GatherViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is gather Fragment"
    }
    val text: LiveData<String> = _text
}