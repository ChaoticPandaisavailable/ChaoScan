package com.scanclone.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.scanclone.model.GalleryImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryRepository(private val contentResolver: ContentResolver) {
    suspend fun loadImages(limit: Int = Int.MAX_VALUE): List<GalleryImage> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        val images = mutableListOf<GalleryImage>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

            while (cursor.moveToNext() && images.size < limit) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                images += GalleryImage(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameColumn) ?: "image-$id",
                    dateAddedMillis = cursor.getLong(dateColumn) * 1_000L,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn)
                )
            }
        }
        images
    }
}
