package com.scanclone.model

import android.net.Uri

data class GalleryImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedMillis: Long,
    val width: Int,
    val height: Int
)
