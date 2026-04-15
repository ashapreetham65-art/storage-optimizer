package com.example.storageoptimizer.data

// Pure in-memory store. No coroutines, no Engine calls, no Android dependencies.
// The ViewModel writes here after a scan; screens read from here via the ViewModel.
// All data is lost when the app process is killed — this is intentional for V8.
class ImageRepository {

    private var images:        List<ImageItem>       = emptyList()
    private var exactGroups:   List<List<ImageItem>> = emptyList()
    private var similarGroups: List<List<ImageItem>> = emptyList()

    fun store(
        images:        List<ImageItem>,
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        this.images        = images
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups
    }

    fun getImages():        List<ImageItem>       = images
    fun getExactGroups():   List<List<ImageItem>> = exactGroups
    fun getSimilarGroups(): List<List<ImageItem>> = similarGroups

    // True once a scan has completed and data is available for the Gallery
    fun hasData(): Boolean = images.isNotEmpty()

    fun clear() {
        images        = emptyList()
        exactGroups   = emptyList()
        similarGroups = emptyList()
    }
}