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
import com.bumptech.glide.Glide
import com.scanclone.model.DocumentPage

class PageStripAdapter(
    private val pages: MutableList<DocumentPage>,
    private val onPageTapped: (Int) -> Unit,
    private val onAddTapped: () -> Unit,
    private val onBindPageThumbnail: ((ImageView, DocumentPage, Int) -> Unit)? = null
) : RecyclerView.Adapter<PageStripAdapter.PageHolder>() {
    init {
        setHasStableIds(true)
    }

    private val pageItem = 0
    private val addItem = 1

    var selectedIndex: Int = 0
        set(value) {
            val old = field
            field = value
            if (old != value) {
                if (old in 0 until itemCount) notifyItemChanged(old)
                if (value in 0 until itemCount) notifyItemChanged(value)
            }
        }

    override fun getItemViewType(position: Int): Int {
        return if (position == pages.size) addItem else pageItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val root = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(76.dp, 76.dp).apply {
                marginEnd = 8.dp
            }
            setPadding(3.dp, 3.dp, 3.dp, 3.dp)
            clipToOutline = true
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val label = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                24.dp,
                Gravity.BOTTOM
            )
        }
        val plus = TextView(parent.context).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 30f
            setTextColor(Color.rgb(66, 112, 162))
            includeFontPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(image)
        root.addView(label)
        root.addView(plus)
        return PageHolder(root, image, label, plus)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.itemView.background = rounded(
            if (position == selectedIndex && position < pages.size) Color.rgb(27, 110, 243)
            else Color.rgb(236, 240, 246),
            14.dp
        )
        holder.image.background = rounded(Color.rgb(247, 249, 252), 12.dp)
        holder.image.clipToOutline = true
        holder.label.visibility = View.VISIBLE
        holder.label.setBackgroundColor(Color.argb(168, 0, 0, 0))

        if (position == pages.size) {
            Glide.with(holder.image).clear(holder.image)
            holder.image.tag = null
            holder.image.setImageDrawable(null)
            holder.image.rotation = 0f
            holder.image.setBackgroundColor(Color.TRANSPARENT)
            holder.label.visibility = View.GONE
            holder.plus.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onAddTapped() }
            return
        }

        val page = pages[position]
        holder.plus.visibility = View.GONE
        holder.image.rotation = 0f
        if (onBindPageThumbnail != null) {
            onBindPageThumbnail.invoke(holder.image, page, position)
        } else {
            holder.image.rotation = page.rotationDegrees.toFloat()
            Glide.with(holder.image)
                .load(page.sourceUri)
                .override(180, 180)
                .centerCrop()
                .into(holder.image)
        }
        holder.label.text = (position + 1).toString()
        holder.itemView.setOnClickListener { onPageTapped(position) }
    }

    override fun getItemId(position: Int): Long {
        return if (position == pages.size) Long.MAX_VALUE else pages[position].stableId
    }

    override fun getItemCount(): Int = pages.size + 1

    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in pages.indices || to !in pages.indices) return
        val moved = pages.removeAt(from)
        pages.add(to, moved)
        notifyItemMoved(from, to)
    }

    fun updateVisibleLabels(recyclerView: RecyclerView) {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val holder = recyclerView.getChildViewHolder(child) as? PageHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position in 0 until pages.size) {
                holder.label.text = (position + 1).toString()
                holder.label.visibility = View.VISIBLE
                holder.plus.visibility = View.GONE
                holder.itemView.setOnClickListener { onPageTapped(position) }
            } else if (position == pages.size) {
                holder.label.visibility = View.GONE
                holder.plus.visibility = View.VISIBLE
                holder.itemView.setOnClickListener { onAddTapped() }
            }
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    class PageHolder(
        itemView: View,
        val image: ImageView,
        val label: TextView,
        val plus: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
