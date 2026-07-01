package com.scanclone.data

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.luck.picture.lib.engine.ImageEngine

class GlideImageEngine private constructor() : ImageEngine {
    override fun loadImage(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).transition(DrawableTransitionOptions.withCrossFade()).into(imageView)
    }

    override fun loadImage(context: Context, imageView: ImageView, url: String, maxWidth: Int, maxHeight: Int) {
        Glide.with(context)
            .load(url)
            .override(maxWidth, maxHeight)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(imageView)
    }

    override fun loadAlbumCover(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).centerCrop().into(imageView)
    }

    override fun loadGridImage(context: Context, url: String, imageView: ImageView) {
        Glide.with(context).load(url).centerCrop().into(imageView)
    }

    override fun pauseRequests(context: Context) {
        Glide.with(context).pauseRequests()
    }

    override fun resumeRequests(context: Context) {
        Glide.with(context).resumeRequests()
    }

    companion object {
        private val INSTANCE = GlideImageEngine()

        fun create(): GlideImageEngine = INSTANCE
    }
}
