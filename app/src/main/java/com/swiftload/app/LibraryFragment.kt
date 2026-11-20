package com.swiftload.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Play tab: finished downloads as Videos | Music | Files, played with the built-in player. */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    private var section = 0 // 0 videos, 1 music, 2 files
    private var all: List<DownloadItem> = emptyList()
    private lateinit var empty: TextView
    private val adapter = LibraryAdapter(
        onClick = { item, sectionItems -> play(item, sectionItems) },
        onLongPress = { ItemActions.showMenu(requireActivity(), it) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        empty = view.findViewById(R.id.empty)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator = null

        val tabs = view.findViewById<TabLayout>(R.id.tabs)
        tabs.getTabAt(section)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { section = t.position; render() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) { list.smoothScrollToPosition(0) }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.items.collect { all = it.filter { d -> d.status == Status.DONE && d.fileUri != null }; render() }
            }
        }
    }

    private fun inSection(item: DownloadItem, s: Int) = when (s) {
        0 -> item.category == Category.VIDEO
        1 -> item.category == Category.AUDIO
        else -> item.category != Category.VIDEO && item.category != Category.AUDIO
    }

    private fun render() {
        val items = all.filter { inSection(it, section) }
        adapter.submit(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(
            when (section) {
                0 -> R.string.library_empty_videos
                1 -> R.string.library_empty_music
                else -> R.string.library_empty_files
            }
        )
    }

    private fun play(item: DownloadItem, sectionItems: List<DownloadItem>) {
        val index = sectionItems.indexOf(item).coerceAtLeast(0)
        when (item.category) {
            Category.VIDEO -> PlayerActivity.playVideos(requireContext(), sectionItems, index)
            // Whole Music list = playlist, so next / previous work (also from the notification)
            Category.AUDIO -> PlayerActivity.playMusic(requireContext(), sectionItems, index)
            else -> ItemActions.open(requireActivity(), item)
        }
    }
}

/** Finished downloads with an empty AdsProvider slot after every 6 rows. */
class LibraryAdapter(
    private val onClick: (DownloadItem, List<DownloadItem>) -> Unit,
    private val onLongPress: (DownloadItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Item(val item: DownloadItem) : Row()
        data class Ad(val n: Int) : Row()
    }

    private var items: List<DownloadItem> = emptyList()
    private var rows: List<Row> = emptyList()
    private val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<DownloadItem>) {
        if (list == items) return
        items = list
        val r = mutableListOf<Row>()
        list.forEachIndexed { i, item ->
            r += Row.Item(item)
            if ((i + 1) % AdsProvider.LIBRARY_EVERY == 0) r += Row.Ad(i)
        }
        rows = r
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is Row.Ad) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 1) {
            object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
        } else DownloadRowVH.create(parent)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Ad -> AdsProvider.bind(holder.itemView as FrameLayout, AdsProvider.SLOT_LIBRARY_LIST)
            is Row.Item -> bindItem(holder as DownloadRowVH, row.item)
        }
    }

    private fun bindItem(h: DownloadRowVH, it: DownloadItem) {
        h.bindThumb(it)
        h.title.text = it.title
        h.progress.visibility = View.GONE
        h.meta.text = listOf(
            Storage.formatSize(it.sizeBytes),
            if (it.category == Category.VIDEO || it.category == Category.AUDIO) formatDuration(it.durationMs) else "",
            dateFormat.format(Date(it.createdAt)),
        ).filter { s -> s.isNotEmpty() }.joinToString("  •  ")
        h.meta.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_secondary))
        h.action.setImageResource(
            if (it.category == Category.VIDEO || it.category == Category.AUDIO) android.R.drawable.ic_media_play
            else android.R.drawable.ic_menu_view
        )
        h.action.setOnClickListener { _ -> onClick(it, items) }
        h.itemView.setOnClickListener { _ -> onClick(it, items) }
        h.itemView.setOnLongClickListener { _ -> onLongPress(it); true }
    }
}
