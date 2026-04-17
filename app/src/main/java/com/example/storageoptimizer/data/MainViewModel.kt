package com.example.storageoptimizer.data

import android.content.ContentResolver
import android.content.ContentUris
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

// MainViewModel is the single source of truth for the entire session.
// It is constructed with a Repository so the DAO (and therefore Context)
// never leaks into the ViewModel directly.
class MainViewModel(private val repository: ImageRepository) : ViewModel() {

    private val hashSemaphore = Semaphore(4)

    // ── StateFlows exposed to UI ─────────────────────────────────────────────

    private val _images        = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _exactGroups   = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val exactGroups: StateFlow<List<List<ImageItem>>> = _exactGroups.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val similarGroups: StateFlow<List<List<ImageItem>>> = _similarGroups.asStateFlow()

    private val _isScanning    = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // True while the DB is being read on startup — HomeScreen can show a
    // loading indicator or simply let the UI appear empty until data arrives.
    private val _isLoadingFromDb = MutableStateFlow(false)
    val isLoadingFromDb: StateFlow<Boolean> = _isLoadingFromDb.asStateFlow()

    // ── Init: restore persisted data on construction ─────────────────────────

    // Called automatically when the ViewModel is first created.
    // No screen needs to trigger this — it just happens.
    init {
        loadFromDb()
    }

    private fun loadFromDb() {
        viewModelScope.launch {
            _isLoadingFromDb.value = true
            val saved = withContext(Dispatchers.IO) { repository.loadFromDb() }

            if (saved.isNotEmpty()) {
                // Re-derive groups from the saved hashes — fast, no re-hashing.
                // Both passes run in parallel.
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
                // Also store groups back into the in-memory cache
                repository.saveAll(saved, exact, similar)

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

    // ── Full scan ────────────────────────────────────────────────────────────

    fun scan(contentResolver: ContentResolver) {
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true

            // 1. Load metadata
            val baseImages = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            // 2. Hash all images (semaphore caps concurrency at 4)
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
                    val ed = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 0) }
                    val sd = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 5) }
                    exact   = ed.await()
                    similar = sd.await()
                }
            }

            // 4. Persist to DB and update memory + StateFlows
            withContext(Dispatchers.IO) {
                repository.saveAll(hashedImages, exact, similar)
            }
            _images.value        = hashedImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            _isScanning.value    = false
        }
    }

    // ── Deletion ─────────────────────────────────────────────────────────────

    fun onDeleteConfirmed(deletedIds: Set<Long>) {
        viewModelScope.launch {
            val updatedImages = repository.getImages().filter { it.id !in deletedIds }
            val newExact   = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 0)
            val newSimilar = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 5)

            // Sync DB: remove only the deleted rows (no need to re-insert everything)
            withContext(Dispatchers.IO) {
                repository.deleteImages(deletedIds, newExact, newSimilar)
            }

            _images.value        = updatedImages
            _exactGroups.value   = newExact
            _similarGroups.value = newSimilar
        }
    }

    // Pre-Android R: delete via ContentResolver directly (no system dialog)
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

    // Duplicates tab: pre-select all but the largest copy in each group
    fun autoSelectedDuplicateIds(): Set<Long> =
        repository.getExactGroups().flatMap { group ->
            group.sortedByDescending { it.size }.drop(1)
        }.map { it.id }.toSet()

    // ── Factory ──────────────────────────────────────────────────────────────

    // ViewModelProvider.Factory is required because MainViewModel now has a
    // constructor parameter (repository). Without this, the default factory
    // crashes with "no default constructor found".
    class Factory(private val repository: ImageRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(repository) as T
        }
    }
}