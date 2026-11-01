package com.sxsbsxs.bilivdl.ui

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sxsbsxs.bilivdl.data.BangumiSearchResult

class BangumiResultAdapter(private val onClick: (BangumiSearchResult) -> Unit) :
    RecyclerView.Adapter<BangumiResultAdapter.Holder>() {
    private val items = mutableListOf<BangumiSearchResult>()

    fun submit(values: List<BangumiSearchResult>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = context.getDrawable(com.sxsbsxs.bilivdl.R.drawable.bg_empty_card)
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (9 * density).toInt()
            }
        }
        val title = TextView(context).apply {
            textSize = 15f
            setTextColor(context.getColor(com.sxsbsxs.bilivdl.R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
        }
        val detail = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(com.sxsbsxs.bilivdl.R.color.text_secondary))
            setPadding(0, (5 * density).toInt(), 0, 0)
            maxLines = 3
        }
        root.addView(title)
        root.addView(detail)
        return Holder(root, title, detail)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.detail.text = listOf(item.newestEpisode, item.description, "点击查看剧集")
            .filter { it.isNotBlank() }.joinToString(" · ")
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(root: LinearLayout, val title: TextView, val detail: TextView) : RecyclerView.ViewHolder(root)
}
