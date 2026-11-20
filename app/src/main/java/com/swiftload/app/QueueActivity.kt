package com.swiftload.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/**
 * Running, waiting and scheduled downloads. Long-press and drag a waiting / scheduled item to change
 * the order: the download service always starts the first waiting item when a slot is free.
 * Tap an item for Start now / Change time / Cancel.
 */
class QueueActivity : AppCompatActivity() {

    private val adapter = QueueAdapter()
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(getColor(R.color.text_primary))
            setNavigationOnClickListener { finish() }
        }
        val empty = findViewById<View>(R.id.empty)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.isMovable(vh.bindingAdapterPosition)) super.getMovementFlags(rv, vh) else 0

            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                if (!adapter.isMovable(to.bindingAdapterPosition)) return false
                dragging = true
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (dragging) { dragging = false; adapter.saveOrder() }
            }
        }).attachToRecyclerView(list)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.items.collect { all ->
                    if (dragging) return@collect
                    val running = all.filter { it.status == Status.RUNNING }
                    val waiting = all.filter { it.status == Status.QUEUED || it.status == Status.SCHEDULED }
                        .sortedWith(compareBy<DownloadItem> { if (it.status == Status.QUEUED) 0 else 1 }.thenBy { it.queueOrder })
                    adapter.submit(running, waiting)
                    empty.visibility = if (running.isEmpty() && waiting.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private inner class QueueAdapter : RecyclerView.Adapter<DownloadRowVH>() {
        private var running: List<DownloadItem> = emptyList()
        private val waiting = mutableListOf<DownloadItem>()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(r: List<DownloadItem>, w: List<DownloadItem>) {
            running = r
            waiting.clear(); waiting.addAll(w)
            notifyDataSetChanged()
        }

        fun isMovable(position: Int) = position >= running.size && position < itemCount

        fun move(from: Int, to: Int) {
            val item = waiting.removeAt(from - running.size)
            waiting.add(to - running.size, item)
            notifyItemMoved(from, to)
        }

        /** New queue order = the order on screen. */
        fun saveOrder() {
            val base = System.currentTimeMillis() - waiting.size * 1000L
            waiting.forEachIndexed { i, item ->
                DownloadStore.update(item.id, persist = i == waiting.lastIndex) { it.copy(queueOrder = base + i * 1000L) }
            }
        }

        override fun getItemCount() = running.size + waiting.size

        private fun itemAt(position: Int) = if (position < running.size) running[position] else waiting[position - running.size]

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = DownloadRowVH.create(parent)

        override fun onBindViewHolder(h: DownloadRowVH, position: Int) {
            val item = itemAt(position)
            h.bindStatus(item)
            h.action.setOnClickListener { ItemActions.primary(this@QueueActivity, item) }
            h.itemView.setOnClickListener { ItemActions.showMenu(this@QueueActivity, item) }
            h.itemView.setOnLongClickListener(null)
            h.itemView.isLongClickable = false
        }
    }
}
