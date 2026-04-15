package com.example.storageoptimizer.data

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Shared between HomeScreen and GalleryScreen via the NavGraph.
// HomeScreen reads isScanning and writes scanRequested.
// GalleryScreen reads scanRequested, runs the scan, and updates isScanning.
class ScanViewModel : ViewModel() {

    // true while a scan is in progress — HomeScreen shows "Scanning..." text
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // HomeScreen flips this to true to ask GalleryScreen to start a scan
    private val _scanRequested = MutableStateFlow(false)
    val scanRequested: StateFlow<Boolean> = _scanRequested.asStateFlow()

    fun requestScan() {
        _scanRequested.value = true
    }

    // Called by GalleryScreen once it has picked up the request
    fun consumeScanRequest() {
        _scanRequested.value = false
    }

    fun setScanningState(scanning: Boolean) {
        _isScanning.value = scanning
    }
}