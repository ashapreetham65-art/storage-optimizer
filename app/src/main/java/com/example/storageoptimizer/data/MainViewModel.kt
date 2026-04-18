package com.example.storageoptimizer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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

class MainViewModel(
    private val repository: ImageRepository,
    private val prefs:      SharedPreferences
) : ViewModel() {

    companion object {
        private const val KEY_LAST_SCANNED = "last_scanned_at_ms"
    }

    private val hashSemaphore = Semaphore(4)

    // ── StateFlows ───────────────────────────────────────────────────────────

    private val _images        = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _exactGroups   = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val exactGroups: StateFlow<List<List<ImageItem>>> = _exactGroups.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val similarGroups: StateFlow<List<List<ImageItem>>> = _similarGroups.asStateFlow()

    private val _isScanning      = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isLoadingFromDb = MutableStateFlow(false)
    val isLoadingFromDb: StateFlow<Boolean> = _isLoadingFromDb.asStateFlow()

    // Epoch milliseconds of the last FULL scan (scan() only, not refresh()).
    // Loaded from SharedPreferences on startup so it survives app restarts.
    private val _lastScannedAt = MutableStateFlow<Long?>(
        prefs.getLong(KEY_LAST_SCANNED, -1L).takeIf { it != -1L }
    )
    val lastScannedAt: StateFlow<Long?> = _lastScannedAt.asStateFlow()

    // ── Init ─────────────────────────────────────────────────────────────────

    init { loadFromDb() }

    private fun loadFromDb() {
        viewModelScope.launch {
            _isLoadingFromDb.value = true
            val saved = withContext(Dispatchers.IO) { repository.loadFromDb() }

            if (saved.isNotEmpty()) {
                // Re-derive groups in parallel — no re-hashing, just BFS on saved hashes
                val exact: List<List<ImageItem>>
                val similar: List<List<ImageItem>>
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val ed = async { ImageEngine.findGroupsByThreshold(saved, threshold = 0) }
                        val sd = async { ImageEngine.findGroupsByThreshold(saved, threshold = 5) }
                        exact   = ed.await()
                        similar = sd.await()
                    }
                }
                // Store groups in memory only — no DB write on startup (V9 bug fixed)
                repository.storeGroupsInMemory(exact, similar)

                _images.value        = saved
                _exactGroups.value   = exact
                _similarGroups.value = similar
            }
            _isLoadingFromDb.value = false
        }
    }

    // ── Derived values ───────────────────────────────────────────────────────

    fun hasData(): Boolean = repository.hasData()

    val reclaimableBytes: Long
        get() = repository.getExactGroups().sumOf { group ->
            group.sortedByDescending { it.size }.drop(1).sumOf { it.size }
        }

    // ── Full scan (first time / HomeScreen "Scan Storage") ───────────────────
    // Used when DB is empty. Hashes every image and saves everything to DB.

    fun scan(contentResolver: ContentResolver) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true

            val baseImages = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            val hashedImages = hashImages(baseImages, contentResolver)

            val exact: List<List<ImageItem>>
            val similar: List<List<ImageItem>>
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val ed = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 0) }
                    val sd = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 5) }
                    exact   = ed.await()
                    similar = sd.await()
                }
            }

            withContext(Dispatchers.IO) {
                repository.saveAll(hashedImages, exact, similar)
            }
            _images.value        = hashedImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            val nowMs = System.currentTimeMillis()
            _lastScannedAt.value = nowMs
            prefs.edit().putLong(KEY_LAST_SCANNED, nowMs).apply()  // persist across restarts
            _isScanning.value    = false
        }
    }

    // ── Incremental refresh (Gallery "Refresh" button) ───────────────────────
    // Compares current MediaStore state against DB.
    // Only hashes images that are new or modified — reuses existing hashes for the rest.

    fun refresh(contentResolver: ContentResolver) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true

            // Step 1: lightweight metadata-only scan (no hashing)
            val currentFromDevice = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            // Step 2: what we already know about
            val dbImages = repository.getImages()
            val dbMap      = dbImages.associateBy { it.id }
            val currentMap = currentFromDevice.associateBy { it.id }

            // Step 3: classify changes
            val newIds      = currentMap.keys - dbMap.keys
            val deletedIds  = dbMap.keys - currentMap.keys
            val modifiedIds = currentMap.keys
                .intersect(dbMap.keys)
                .filter { id ->
                    val current = currentMap.getValue(id)
                    val stored  = dbMap.getValue(id)
                    // Modified = size OR dateModified changed
                    current.size != stored.size || current.dateModified != stored.dateModified
                }.toSet()

            val toHashIds = newIds + modifiedIds

            // Step 4: hash only what needs it
            val rehashed = if (toHashIds.isNotEmpty()) {
                val toHashItems = toHashIds.map { id -> currentMap.getValue(id) }
                hashImages(toHashItems, contentResolver)
            } else {
                emptyList()
            }

            val rehashedMap = rehashed.associateBy { it.id }

            // Step 5: build final merged list
            // Start from DB images, remove deleted, replace modified, add new
            val finalImages = (dbImages
                .filter { it.id !in deletedIds && it.id !in modifiedIds }
                    + rehashedMap.values)
                .sortedByDescending { it.id }   // newest first

            // Step 6: update DB incrementally (no clearAll)
            val toUpsert = rehashed  // new + modified, now with hashes
            withContext(Dispatchers.IO) {
                repository.applyIncrementalUpdate(
                    finalImages   = finalImages,
                    toUpsert      = toUpsert,
                    deletedIds    = deletedIds,
                    exactGroups   = emptyList(),   // rebuilt below
                    similarGroups = emptyList()
                )
            }

            // Step 7: rebuild groups from the merged image set
            val exact: List<List<ImageItem>>
            val similar: List<List<ImageItem>>
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val ed = async { ImageEngine.findGroupsByThreshold(finalImages, threshold = 0) }
                    val sd = async { ImageEngine.findGroupsByThreshold(finalImages, threshold = 5) }
                    exact   = ed.await()
                    similar = sd.await()
                }
            }

            // Update in-memory groups (they were already set to empty above)
            repository.storeGroupsInMemory(exact, similar)

            _images.value        = finalImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            val nowMs = System.currentTimeMillis()
            _lastScannedAt.value = nowMs
            prefs.edit().putLong(KEY_LAST_SCANNED, nowMs).apply()
            _isScanning.value    = false
        }
    }

    // ── Shared hashing helper ─────────────────────────────────────────────────
    // Used by both scan() and refresh() — keeps the semaphore logic in one place.

    private suspend fun hashImages(
        items: List<ImageItem>,
        contentResolver: ContentResolver
    ): List<ImageItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { image ->
                async {
                    hashSemaphore.withPermit {
                        image.copy(
                            hash = ImageEngine.calculatePerceptualHash(image.uri, contentResolver)
                        )
                    }
                }
            }.awaitAll()
        }
    }

    // ── Deletion ─────────────────────────────────────────────────────────────

    fun onDeleteConfirmed(deletedIds: Set<Long>) {
        viewModelScope.launch {
            val updatedImages = repository.getImages().filter { it.id !in deletedIds }
            val newExact   = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 0)
            val newSimilar = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 5)

            withContext(Dispatchers.IO) {
                repository.deleteImages(deletedIds, newExact, newSimilar)
            }

            _images.value        = updatedImages
            _exactGroups.value   = newExact
            _similarGroups.value = newSimilar
        }
    }

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

    fun autoSelectedDuplicateIds(): Set<Long> =
        repository.getExactGroups().flatMap { group ->
            group.sortedByDescending { it.size }.drop(1)
        }.map { it.id }.toSet()

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(
        private val repository: ImageRepository,
        private val prefs:      SharedPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository, prefs) as T
    }
}