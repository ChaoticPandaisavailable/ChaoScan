package com.scanclone.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scanclone.model.GalleryImage

class GalleryAdapter(
    private val onImageTapped: (GalleryImage) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ImageHolder>() {
    var images: List<GalleryImage> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectedOrder: Map<Long, Int> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageHolder {
        val itemSize = parent.resources.displayMetrics.widthPixels / 3
        val root = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(itemSize, itemSize)
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val overlay = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(Color.rgb(36, 163, 140), 15.dp)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.TOP or Gravity.END)
        }
        root.addView(image)
        root.addView(overlay)
        return ImageHolder(root, image, overlay)
    }

    override fun onBindViewHolder(holder: ImageHolder, position: Int) {
        val item = images[position]
        holder.image.setImageURI(item.uri)
        holder.itemView.setOnClickListener { onImageTapped(item) }
        val order = selectedOrder[item.id]
        if (order == null) {
            holder.order.visibility = View.GONE
            holder.itemView.alpha = 1f
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        } else {
            holder.order.visibility = View.VISIBLE
            holder.order.text = order.toString()
            holder.itemView.alpha = 0.78f
            holder.itemView.setBackgroundColor(Color.rgb(36, 163, 140))
        }
    }

    override fun getItemCount(): Int = images.size

    class ImageHolder(
        itemView: View,
        val image: ImageView,
        val order: TextView
    ) : RecyclerView.ViewHolder(itemView)

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }
}

val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
