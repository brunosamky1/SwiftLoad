package com.swiftload.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Download tab: link/search bar, Search | Music | More, active downloads on top.
 *  - a link   -> the same detect & download pop-up as Share
 *  - words    -> web video search in the in-app browser (the media sniffer finds the files)
 *  - Music    -> SoundCloud search with MP3 download
 * YouTube is not supported.
 */
class DownloadFragment : Fragment(R.layout.fragment_download) {

    private enum class Tab { SEARCH, MUSIC, MORE }

    private var tab = Tab.SEARCH
    private var musicResults: List<SearchResult> = emptyList()
    private var musicMessage: String? = null
    private var loading = false
    private var searchJob: Job? = null
    private var activeCount = 0

    private lateinit var input: EditText
    private lateinit var tabs: TabLayout
    private lateinit var list: RecyclerView
    private lateinit var statusBox: View
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var btnQueue: MaterialButton

    private val activeAdapter = DownloadAdapter(
        onAction = { ItemActions.primary(requireActivity(), it) },
        onLongPress = { ItemActions.showMenu(requireActivity(), it) },
    )
    private val searchAdapter = SearchAdapter { result, mode -> download(result, mode) }
    private val sitesAdapter = SitesAdapter { site -> BrowserActivity.open(requireContext(), site.url, site.name) }
    private val concat = ConcatAdapter(activeAdapter)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        input = view.findViewById(R.id.input)
        tabs = view.findViewById(R.id.tabs)
        list = view.findViewById(R.id.list)
        statusBox = view.findViewById(R.id.statusBox)
        spinner = view.findViewById(R.id.spinner)
        statusText = view.findViewById(R.id.statusText)
        btnQueue = view.findViewById(R.id.btnQueue)
        val btnClear = view.findViewById<View>(R.id.btnClear)

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = concat
        list.itemAnimator = null

        input.setOnEditorActionListener { _, id, ev ->
            val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_GO || enter) { submit(); true } else false
        }
        input.doAfterTextChanged { btnClear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        btnClear.setOnClickListener { input.setText("") }
        view.findViewById<View>(R.id.btnGo).setOnClickListener { submit() }
        view.findViewById<View>(R.id.btnPaste).setOnClickListener {
            val text = (activity as? MainActivity)?.clipboardText()
            if (text == null) toast("Clipboard is empty") else { input.setText(text); input.setSelection(text.length) }
        }
        btnQueue.setOnClickListener { startActivity(Intent(requireContext(), QueueActivity::class.java)) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) { tab = Tab.values()[t.position]; render() }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        AdsProvider.bind(view.findViewById<FrameLayout>(R.id.adSlot), AdsProvider.SLOT_DOWNLOAD_TAB)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadStore.items.collect { all ->
                    // Running first, then waiting / scheduled in queue order, then failed & cancelled
                    val active = all.filter { it.status != Status.DONE }.sortedWith(
                        compareBy<DownloadItem> {
                            when (it.status) {
                                Status.RUNNING -> 0; Status.QUEUED -> 1; Status.SCHEDULED -> 2; else -> 3
                            }
                        }.thenBy { if (it.status == Status.QUEUED || it.status == Status.SCHEDULED) it.queueOrder else -it.createdAt }
                    )
                    val grew = active.size > activeCount
                    activeCount = active.size
                    activeAdapter.submitList(active) { if (grew) list.scrollToPosition(0) }
                    val queued = all.count { it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.SCHEDULED }
                    btnQueue.visibility = if (queued > 0) View.VISIBLE else View.GONE
                    btnQueue.text = "Queue ($queued)"
                    renderStatus()
                }
            }
        }
        render()
    }

    private val DOMAIN_LIKE = Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""")

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) { toast(if (tab == Tab.MUSIC) "Type a song or artist" else "Paste a link or type words to search"); return }
        hideKeyboard()
        val isLink = text.contains("://") || (!text.contains(' ') && DOMAIN_LIKE.matches(text))
        val url = if (isLink) Detector.extractUrl(text) else null
        if (url != null) {
            if (Blocklist.isBlocked(url)) { toast(Blocklist.MESSAGE); return }
            input.setText("")
            MainActivity.openShareFlow(requireContext(), url)
            return
        }
        if (tab == Tab.MUSIC) {
            searchMusic(text)
        } else {
            // Web video search in the in-app browser; open a result and tap "Download video"
            val q = URLEncoder.encode(text, "UTF-8")
            BrowserActivity.open(requireContext(), "https://www.google.com/search?q=$q&tbm=vid", "Search")
        }
    }

    private fun searchMusic(query: String) {
        searchJob?.cancel()
        MusicSearch.cancel()
        loading = true
        musicResults = emptyList()
        musicMessage = null
        render()
        val appContext = requireContext().applicationContext
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            var results: List<SearchResult> = emptyList()
            var message: String? = null
            try {
                results = MusicSearch.search(appContext, query)
                if (results.isEmpty()) message = "No results for “$query”."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = friendlySearchError(e)
            }
            musicResults = results
            musicMessage = message
            loading = false
            render()
        }
    }

    private fun friendlySearchError(e: Throwable): String {
        val m = e.message ?: ""
        return when {
            m.contains("Unable to resolve host", true) || m.contains("UnknownHost", true) ||
                m.contains("Failed to resolve", true) || m.contains("getaddrinfo", true) ->
                "No internet connection.\nCheck your connection and try again."
            m.contains("canceled", true) || m.contains("cancelled", true) -> "Search stopped."
            else -> "Search failed. Please try again in a moment.\n\n" +
                (m.lines().lastOrNull { it.contains("ERROR") } ?: m.lines().firstOrNull() ?: "").removePrefix("ERROR:").trim().take(160)
        }
    }

    private fun download(result: SearchResult, mode: Mode) {
        val cat = if (mode == Mode.AUDIO_MP3) Category.AUDIO else Category.VIDEO
        DownloadService.enqueue(requireContext(), result.url, result.title, mode, cat, thumbnailUrl = result.thumbnail)
            ?.let { toast("Downloading ${if (mode == Mode.AUDIO_MP3) "MP3" else "video"}: ${result.title}") }
    }

    /** Puts the right content under the active downloads and updates the status text. */
    private fun render() {
        if (!::input.isInitialized) return
        val content: RecyclerView.Adapter<*>? = when (tab) {
            Tab.MUSIC -> searchAdapter
            Tab.MORE -> sitesAdapter
            Tab.SEARCH -> null
        }
        concat.adapters.filter { it !== activeAdapter && it !== content }.forEach { concat.removeAdapter(it) }
        if (content != null && !concat.adapters.contains(content)) concat.addAdapter(content)
        if (tab == Tab.MUSIC) searchAdapter.submit(musicResults, audioOnly = true)
        input.setHint(if (tab == Tab.MUSIC) R.string.music_hint else R.string.url_hint)
        renderStatus()
    }

    private fun renderStatus() {
        if (!::input.isInitialized) return
        val busy = loading && tab == Tab.MUSIC
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        when {
            tab == Tab.MORE -> statusBox.visibility = View.GONE
            busy -> { statusBox.visibility = View.VISIBLE; statusText.text = "Searching music…" }
            tab == Tab.MUSIC && musicMessage != null -> { statusBox.visibility = View.VISIBLE; statusText.text = musicMessage }
            tab == Tab.MUSIC && musicResults.isEmpty() && activeCount == 0 -> {
                statusBox.visibility = View.VISIBLE; statusText.setText(R.string.music_empty)
            }
            tab == Tab.SEARCH && activeCount == 0 -> {
                statusBox.visibility = View.VISIBLE; statusText.setText(R.string.search_empty)
            }
            else -> statusBox.visibility = View.GONE
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}

/** Orange filled button (the default choice) or orange outlined button. */
fun MaterialButton.styleChoice(filled: Boolean) {
    val orange = ContextCompat.getColor(context, R.color.brand)
    if (filled) {
        backgroundTintList = ColorStateList.valueOf(orange)
        setTextColor(ContextCompat.getColor(context, R.color.on_brand))
        strokeWidth = 0
    } else {
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        setTextColor(orange)
        strokeColor = ColorStateList.valueOf(orange)
        strokeWidth = (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}

// ---------------------------------------------------------------- search results

class SearchAdapter(
    private val onDownload: (SearchResult, Mode) -> Unit,
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private var items: List<SearchResult> = emptyList()
    private var audioOnly = false

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchResult>, audioOnly: Boolean) {
        if (list === items && audioOnly == this.audioOnly) return
        items = list
        this.audioOnly = audioOnly
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val duration: TextView = v.findViewById(R.id.duration)
        val title: TextView = v.findViewById(R.id.title)
        val channel: TextView = v.findViewById(R.id.channel)
        val mp4: MaterialButton = v.findViewById(R.id.btnMp4)
        val mp3: MaterialButton = v.findViewById(R.id.btnMp3)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        h.title.text = r.title
        h.channel.text = r.channel
        h.channel.visibility = if (r.channel.isEmpty()) View.GONE else View.VISIBLE
        h.duration.text = formatDuration(r.durationSec * 1000)
        h.duration.visibility = if (r.durationSec > 0) View.VISIBLE else View.GONE
        h.thumb.load(r.thumbnail) {
            crossfade(true)
            transformations(RoundedCornersTransformation(10 * h.itemView.resources.displayMetrics.density))
        }
        // Music results are audio: MP3 only
        h.mp4.visibility = if (audioOnly) View.GONE else View.VISIBLE
        h.mp4.styleChoice(filled = !audioOnly)
        h.mp3.styleChoice(filled = audioOnly)
        h.mp4.setOnClickListener { onDownload(r, Mode.VIDEO) }
        h.mp3.setOnClickListener { onDownload(r, Mode.AUDIO_MP3) }
    }
}

// ---------------------------------------------------------------- "More" site grid

data class Site(val name: String, val url: String, val color: Int, val letter: String)

/** Our own simple site buttons: a colored circle with a letter (no logos). */
class SitesAdapter(private val onClick: (Site) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val sites = listOf(
        Site("TikTok", "https://www.tiktok.com/", 0xFF26C6DA.toInt(), "T"),
        Site("Facebook", "https://m.facebook.com/", 0xFF5C7CFA.toInt(), "F"),
        Site("Instagram", "https://www.instagram.com/", 0xFFE76F9A.toInt(), "I"),
        Site("X", "https://x.com/", 0xFF8D99AE.toInt(), "X"),
        Site("Threads", "https://www.threads.net/", 0xFFB197FC.toInt(), "Th"),
        Site("Pinterest", "https://www.pinterest.com/", 0xFFFF6B6B.toInt(), "P"),
        Site("Vimeo", "https://vimeo.com/", 0xFF4DABF7.toInt(), "V"),
        Site("Dailymotion", "https://www.dailymotion.com/", 0xFF63E6BE.toInt(), "D"),
        Site("SoundCloud", "https://m.soundcloud.com/", 0xFFFFA94D.toInt(), "S"),
        Site("Google", "https://www.google.com/", 0xFF94D82D.toInt(), "G"),
    )

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density
        val grid = GridLayout(ctx).apply {
            columnCount = 4
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        sites.forEach { site ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                isClickable = true
                isFocusable = true
                val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                background = ta.getDrawable(0)
                ta.recycle()
                setOnClickListener { onClick(site) }
            }
            val circle = TextView(ctx).apply {
                text = site.letter
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(site.color) }
            }
            cell.addView(circle, LinearLayout.LayoutParams((54 * dp).toInt(), (54 * dp).toInt()))
            cell.addView(TextView(ctx).apply {
                text = site.name
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 1
                setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt()
            })
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply { width = 0 })
        }
        return object : RecyclerView.ViewHolder(grid) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}
