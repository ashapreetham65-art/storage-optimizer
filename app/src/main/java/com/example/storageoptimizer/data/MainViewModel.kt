package com.example.storageoptimizer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.storageoptimizer.engine.ImageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Single source of truth for the entire app session.
// Shared between HomeScreen and GalleryScreen via AppNavGraph.
// Survives navigation — data persists until the process is killed.
class MainViewModel : ViewModel() {

    private val repository   = ImageRepository()
    private val hashSemaphore = Semaphore(4)

    // ── Public state exposed to UI ──────────────────────────────────────────

    private val _images        = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _exactGroups   = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val exactGroups: StateFlow<List<List<ImageItem>>> = _exactGroups.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val similarGroups: StateFlow<List<List<ImageItem>>> = _similarGroups.asStateFlow()

    private val _isScanning    = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ── Derived convenience values ──────────────────────────────────────────

    fun hasData(): Boolean = repository.hasData()

    // Total bytes occupied by images currently in the list
    val totalImageBytes: Long
        get() = repository.getImages().sumOf { it.size }

    // Bytes that would be freed if all auto-selected duplicates were deleted
    val reclaimableBytes: Long
        get() = repository.getExactGroups().sumOf { group ->
            group.sortedByDescending { it.size }.drop(1).sumOf { it.size }
        }

    // ── Scan ────────────────────────────────────────────────────────────────

    // Called from HomeScreen and Gallery "Refresh" button.
    // Runs entirely on IO threads; updates StateFlows when done.
    fun scan(contentResolver: ContentResolver) {
        if (_isScanning.value) return   // ignore tap during ongoing scan

        viewModelScope.launch {
            _isScanning.value = true

            // 1. Load image metadata from MediaStore
            val baseImages = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            // 2. Hash all images concurrently (semaphore limits parallelism to 4)
            val hashedImages = withContext(Dispatchers.IO) {
                coroutineScope {
                    baseImages.map { image ->
                        async {
                            hashSemaphore.withPermit {
                                image.copy(
                                    hash = ImageEngine.calculatePerceptualHash(
                                        image.uri, contentResolver
                                    )
                                )
                            }
                        }
                    }.awaitAll()
                }
            }

            // 3. Build both group sets in parallel
            val exact: List<List<ImageItem>>
            val similar: List<List<ImageItem>>
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val exactDeferred   = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 0) }
                    val similarDeferred = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 5) }
                    exact   = exactDeferred.await()
                    similar = similarDeferred.await()
                }
            }

            // 4. Persist in repository and push to StateFlows
            repository.store(hashedImages, exact, similar)
            _images.value        = hashedImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            _isScanning.value    = false
        }
    }

    // ── Deletion ────────────────────────────────────────────────────────────

    // Called after the system delete dialog confirms.
    // Re-builds groups from the surviving images — no re-hash needed
    // because we already have hashes for all remaining images.
    fun onDeleteConfirmed(deletedIds: Set<Long>) {
        val updatedImages = repository.getImages().filter { it.id !in deletedIds }

        val newExact   = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 0)
        val newSimilar = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 5)

        repository.store(updatedImages, newExact, newSimilar)
        _images.value        = updatedImages
        _exactGroups.value   = newExact
        _similarGroups.value = newSimilar
    }

    // ── Auto-select helper (used by GalleryScreen for Duplicates tab) ───────

    // Pre-Android R delete path: no system dialog, delete directly via ContentResolver.
    // Calls onComplete on the main thread when done so GalleryScreen can flip its flag.
    fun viewModelScopeDelete(
        toDelete:        Set<Long>,
        contentResolver: ContentResolver,
        onComplete:      () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { id ->
                contentResolver.delete(
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    ), null, null
                )
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    // Returns the IDs that should be pre-selected for deletion in the Duplicates tab:
    // all copies except the largest one in each exact-duplicate group.
    fun autoSelectedDuplicateIds(): Set<Long> =
        repository.getExactGroups().flatMap { group ->
            group.sortedByDescending { it.size }.drop(1)
        }.map { it.id }.toSet()
}