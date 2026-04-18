package com.example.storageoptimizer.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.example.storageoptimizer.data.ImageItem

object ImageEngine {

    // Reads id, size, AND dateModified from MediaStore.
    // dateModified is needed by the V10 incremental refresh to detect edits.
    // hash is left null — caller is responsible for hashing when needed.
    fun loadImages(contentResolver: ContentResolver): List<ImageItem> {
        val imageList  = mutableListOf<ImageItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED   // seconds since epoch
        )
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )
        cursor?.use {
            val idCol           = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val sizeCol         = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateModifiedCol = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (it.moveToNext()) {
                val id   = it.getLong(idCol)
                val size = it.getLong(sizeCol)
                val dm   = it.getLong(dateModifiedCol)
                val uri  = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                imageList.add(ImageItem(id, uri, null, size, dm))
            }
        }
        return imageList
    }

    // V6.2 dHash — unchanged
    fun calculatePerceptualHash(uri: Uri, contentResolver: ContentResolver): Long? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize      = 4
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val original = android.graphics.BitmapFactory.decodeStream(inputStream, null, opts)
            inputStream.close()
            if (original == null) return null

            val resized = android.graphics.Bitmap.createScaledBitmap(original, 9, 8, true)
            original.recycle()

            val pixels = IntArray(9 * 8)
            resized.getPixels(pixels, 0, 9, 0, 0, 9, 8)
            resized.recycle()

            fun luma(p: Int): Int {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8)  and 0xFF
                val b =  p         and 0xFF
                return (299 * r + 587 * g + 114 * b) / 1000
            }

            var hash = 0L
            var bit  = 0
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    if (luma(pixels[row * 9 + col]) > luma(pixels[row * 9 + col + 1]))
                        hash = hash or (1L shl bit)
                    bit++
                }
            }
            hash
        } catch (e: Exception) { null }
    }

    fun hammingDistance(a: Long, b: Long): Int =
        java.lang.Long.bitCount(a xor b)

    fun findGroupsByThreshold(
        images: List<ImageItem>,
        threshold: Int
    ): List<List<ImageItem>> {
        val valid = images.filter { it.hash != null }
        val n     = valid.size

        val neighbors = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (hammingDistance(valid[i].hash!!, valid[j].hash!!) <= threshold) {
                    neighbors[i].add(j)
                    neighbors[j].add(i)
                }
            }
        }

        val visited = BooleanArray(n)
        val groups  = mutableListOf<List<ImageItem>>()

        for (start in 0 until n) {
            if (visited[start]) continue
            val queue = ArrayDeque<Int>()
            val group = mutableListOf<ImageItem>()
            queue.add(start); visited[start] = true
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                group.add(valid[cur])
                for (nb in neighbors[cur]) {
                    if (!visited[nb]) { visited[nb] = true; queue.add(nb) }
                }
            }
            if (group.size > 1) groups.add(group)
        }

        return groups.sortedByDescending { it.size }
    }
}