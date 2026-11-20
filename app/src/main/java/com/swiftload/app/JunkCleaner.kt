package com.swiftload.app

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Honest junk cleaner: every number is a real file size, every category is a real place on the phone,
 * nothing is selected that could be important (large files, duplicates, not-installed APKs) and
 * nothing is deleted without the user's confirmation.
 */
enum class JunkType(val emoji: String, val title: String, val info: String, val needsFullAccess: Boolean) {
    APP_CACHE("🗃️", "App cache", "VidSaver's own cache and leftovers of unfinished downloads", false),
    TRASH("🗑️", "Trash", "Photos, videos and files already in the phone's trash", false),
    THUMBNAILS("🖼️", "Thumbnails", ".thumbnails folders — the phone re-creates them when needed", true),
    APKS("📦", "APK files", "Installer files. Ones already installed are selected", true),
    TEMP("🧾", "Temporary & log files", ".tmp .log .bak .part .crdownload … older than 24 hours", true),
    EMPTY_DIRS("📁", "Empty folders", "Folders with nothing inside", true),
    LARGE("🐘", "Large files", "Files over 100 MB — not selected, check them yourself", true),
    DUPLICATES("👯", "Duplicate files", "Identical copies — the oldest copy is always kept", true),
}

enum class JunkKind { FILE, DIR_TREE, EMPTY_DIR, MEDIASTORE }

class JunkItem(
    val name: String,
    val path: String?,        // real file path (null for MediaStore trash items)
    val uri: Uri?,            // MediaStore uri for trash items
    val size: Long,
    val modified: Long,       // ms
    val note: String?,
    var selected: Boolean,
    val kind: JunkKind,
    val mime: String? = null,
) {
    private val ext get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val isImage get() = mime?.startsWith("image/") == true || ext in IMAGE_EXT
    val isVideo get() = mime?.startsWith("video/") == true || ext in VIDEO_EXT

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
    }
}

class JunkCategory(val type: JunkType) {
    enum class State { WAITING, SCANNING, DONE, UNAVAILABLE }

    /** Filled on the scan thread; only read by the screen after [state] is DONE. */
    val items = mutableListOf<JunkItem>()
    /** Live total while scanning (shown in the big number). */
    val found = AtomicLong()
    @Volatile var state = State.WAITING
    @Volatile var reason: String? = null
    @Volatile var stopped = false

    fun add(item: JunkItem) { items += item; found.addAndGet(item.size) }
    val totalSize get() = items.sumOf { it.size }
    val selectedItems get() = items.filter { it.selected }
    val selectedSize get() = items.filter { it.selected }.sumOf { it.size }
}

class JunkScanner(private val context: Context, val fullAccess: Boolean) {

    val categories: List<JunkCategory> = JunkType.values().map { JunkCategory(it) }
    fun cat(t: JunkType) = categories[t.ordinal]

    @Volatile var currentPath = ""
    @Volatile var stopRequested = false

    private val root: File = Environment.getExternalStorageDirectory()
    private val now = System.currentTimeMillis()

    val totalFound get() = categories.sumOf { it.found.get() }

    suspend fun run() = withContext(Dispatchers.IO) {
        scanAppCache()
        scanTrash()
        val fileTypes = categories.filter { it.type.needsFullAccess }
        if (!fullAccess) {
            fileTypes.forEach { it.reason = "Needs “All files access”"; it.state = JunkCategory.State.UNAVAILABLE }
            return@withContext
        }
        fileTypes.forEach { it.state = JunkCategory.State.SCANNING }
        walk()
        listOf(JunkType.THUMBNAILS, JunkType.APKS, JunkType.TEMP, JunkType.EMPTY_DIRS).forEach { finish(cat(it)) }
        findDuplicates()
        findLarge()
        currentPath = ""
    }

    private fun finish(c: JunkCategory) {
        c.stopped = stopRequested
        c.state = JunkCategory.State.DONE
    }

    private fun rel(f: File) = f.path.removePrefix(root.path).trimStart('/')

    // ---------------------------------------------------------------- 1. app cache

    private fun scanAppCache() {
        val c = cat(JunkType.APP_CACHE)
        c.state = JunkCategory.State.SCANNING
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                val size = sizeOf(f)
                if (size > 0) c.add(JunkItem(f.name, f.path, null, size, f.lastModified(), "Cache", true,
                    if (f.isDirectory) JunkKind.DIR_TREE else JunkKind.FILE))
            }
        }
        // Temp folders of downloads that are no longer running
        val active = DownloadStore.items.value
            .filter { it.status == Status.RUNNING || it.status == Status.QUEUED }.map { it.id }.toSet()
        File(context.getExternalFilesDir(null) ?: context.filesDir, "tmp").listFiles()?.forEach { f ->
            if (f.name in active) return@forEach
            val size = sizeOf(f)
            c.add(JunkItem(f.name, f.path, null, size, f.lastModified(), "Unfinished download", true,
                if (f.isDirectory) JunkKind.DIR_TREE else JunkKind.FILE))
        }
        finish(c)
    }

    private fun sizeOf(f: File): Long =
        if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()

    // ---------------------------------------------------------------- 2. trash (Android 11+)

    private fun scanTrash() {
        val c = cat(JunkType.TRASH)
        if (Build.VERSION.SDK_INT < 30) {
            c.reason = "Needs Android 11 or newer"
            c.state = JunkCategory.State.UNAVAILABLE
            return
        }
        c.state = JunkCategory.State.SCANNING
        runCatching {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
            val proj = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
            )
            context.contentResolver.query(collection, proj, args, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1) ?: "item $id"
                    c.add(JunkItem(
                        name = name.removePrefix(".trashed-").replace(Regex("""^\d+-"""), ""),
                        path = (cur.getString(4) ?: "") + name,
                        uri = ContentUris.withAppendedId(collection, id),
                        size = cur.getLong(2),
                        modified = cur.getLong(3) * 1000,
                        note = "In trash",
                        selected = true,
                        kind = JunkKind.MEDIASTORE,
                        mime = cur.getString(5),
                    ))
                }
            }
        }.onFailure { c.reason = "Could not read the trash" }
        finish(c)
    }

    // ---------------------------------------------------------------- 3–8. one walk over the storage

    private val tempExt = setOf("tmp", "temp", "log", "bak", "part", "crdownload", "ytdl")
    private val apkExt = setOf("apk", "apks", "xapk")
    private val protectedRoots = setOf(
        "android", "dcim", "pictures", "music", "movies", "download", "documents", "alarms",
        "notifications", "podcasts", "ringtones", "audiobooks", "recordings",
    )
    private val dupCandidates = mutableListOf<File>()
    private val largeCandidates = mutableListOf<File>()
    private val pm: PackageManager = context.packageManager

    private fun walk() {
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && !stopRequested) {
            val dir = stack.removeLast()
            val r = rel(dir).lowercase(Locale.ROOT)
            if (r == "android/data" || r.startsWith("android/data/") || r == "android/obb" || r.startsWith("android/obb/")) continue
            currentPath = dir.path
            val children = dir.listFiles() ?: continue
            if (children.isEmpty()) {
                if (emptyFolderAllowed(dir)) {
                    cat(JunkType.EMPTY_DIRS).add(JunkItem(dir.name, dir.path, null, 0, dir.lastModified(), null, true, JunkKind.EMPTY_DIR))
                }
                continue
            }
            for (f in children) {
                if (runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) continue
                if (f.isDirectory) stack.add(f) else visit(f)
            }
        }
    }

    private fun emptyFolderAllowed(dir: File): Boolean {
        val parts = rel(dir).split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.any { it.startsWith(".") }) return false                 // hidden / app marker folders
        if (parts.first().lowercase(Locale.ROOT) == "android") return false  // system app folders
        if (parts.size == 1 && parts.first().lowercase(Locale.ROOT) in protectedRoots) return false
        return true
    }

    private fun visit(f: File) {
        val name = f.name
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val size = f.length()
        val mod = f.lastModified()
        when {
            f.path.contains("/.thumbnails/", ignoreCase = true) ->
                cat(JunkType.THUMBNAILS).add(JunkItem(name, f.path, null, size, mod, null, true, JunkKind.FILE))
            ext in apkExt -> cat(JunkType.APKS).add(apkItem(f, ext, size, mod))
            ext in tempExt && now - mod > 24 * 3600_000L ->
                cat(JunkType.TEMP).add(JunkItem(name, f.path, null, size, mod, null, true, JunkKind.FILE))
            else -> {
                if (size >= DUP_MIN_SIZE) dupCandidates += f
                if (size > LARGE_SIZE) largeCandidates += f
            }
        }
    }

    /** "Installed" = same app with the same or a newer version is already on the phone -> safe to delete. */
    private fun apkItem(f: File, ext: String, size: Long, mod: Long): JunkItem {
        if (ext != "apk") {
            return JunkItem(f.name, f.path, null, size, mod, "App bundle (${ext.uppercase(Locale.ROOT)})", false, JunkKind.FILE)
        }
        val info = runCatching { pm.getPackageArchiveInfo(f.path, 0) }.getOrNull()
            ?: return JunkItem(f.name, f.path, null, size, mod, "Damaged or unreadable APK", false, JunkKind.FILE)
        info.applicationInfo?.let { it.sourceDir = f.path; it.publicSourceDir = f.path }
        val label = runCatching { info.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: info.packageName
        val installed = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
        val apkVersion = PackageInfoCompat.getLongVersionCode(info)
        return if (installed != null && PackageInfoCompat.getLongVersionCode(installed) >= apkVersion) {
            JunkItem(f.name, f.path, null, size, mod, "$label ${info.versionName ?: ""} — installed", true, JunkKind.FILE)
        } else if (installed != null) {
            JunkItem(f.name, f.path, null, size, mod, "$label ${info.versionName ?: ""} — newer than installed", false, JunkKind.FILE)
        } else {
            JunkItem(f.name, f.path, null, size, mod, "$label ${info.versionName ?: ""} — not installed", false, JunkKind.FILE)
        }
    }

    // ---------------------------------------------------------------- duplicates & large files

    private val duplicatePaths = HashSet<String>()

    private fun findDuplicates() {
        val c = cat(JunkType.DUPLICATES)
        if (stopRequested) { finish(c); return }
        val groups = dupCandidates.groupBy { it.length() }.values.filter { it.size > 1 }
        for (sameSize in groups) {
            if (stopRequested) break
            // 1) MD5 of the first + last 1 MB, 2) full MD5 for bigger files
            val byPartial = sameSize.groupBy { currentPath = it.path; partialHash(it) }.filterKeys { it != null }.values
            for (g in byPartial.filter { it.size > 1 }) {
                if (stopRequested) break
                val confirmed = if (g.first().length() <= 2 * MB) listOf(g)
                else g.groupBy { currentPath = it.path; fullHash(it) }.filterKeys { it != null }.values.toList()
                for (same in confirmed.filter { it.size > 1 }) {
                    val keep = same.minByOrNull { it.lastModified() } ?: continue
                    same.filter { it != keep }.forEach { f ->
                        duplicatePaths += f.path
                        c.add(JunkItem(f.name, f.path, null, f.length(), f.lastModified(),
                            "Copy of ${rel(keep)} (kept)", false, JunkKind.FILE))
                    }
                }
            }
        }
        finish(c)
    }

    private fun findLarge() {
        val c = cat(JunkType.LARGE)
        largeCandidates.filter { it.path !in duplicatePaths }.sortedByDescending { it.length() }.forEach { f ->
            c.add(JunkItem(f.name, f.path, null, f.length(), f.lastModified(), null, false, JunkKind.FILE))
        }
        finish(c)
    }

    private fun partialHash(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        RandomAccessFile(f, "r").use { raf ->
            val len = raf.length()
            val buf = ByteArray(64 * 1024)
            fun read(from: Long, count: Long) {
                raf.seek(from)
                var left = count
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    md.update(buf, 0, n); left -= n
                }
            }
            read(0, minOf(MB, len))
            if (len > MB) read(maxOf(MB, len - MB), len - maxOf(MB, len - MB))
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun fullHash(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (!stopRequested) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        if (stopRequested) null else md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        const val MB = 1024L * 1024
        const val LARGE_SIZE = 100 * MB
        /** Very small files are skipped for duplicates (fast scan; they free almost no space). */
        const val DUP_MIN_SIZE = 100 * 1024L
    }
}

object JunkCleaner {
    /** Deletes normal files/folders. Returns (deleted count, freed bytes) — only what was really deleted. */
    suspend fun deleteFiles(context: Context, items: List<JunkItem>): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var count = 0
        var freed = 0L
        val scanned = mutableListOf<String>()
        for (item in items) {
            val f = File(item.path ?: continue)
            val ok = runCatching {
                when (item.kind) {
                    JunkKind.FILE -> f.delete()
                    JunkKind.DIR_TREE -> f.deleteRecursively()
                    JunkKind.EMPTY_DIR -> (f.listFiles()?.isEmpty() ?: false) && f.delete()
                    JunkKind.MEDIASTORE -> false
                }
            }.getOrDefault(false) && !f.exists()
            if (ok) {
                count++; freed += item.size
                if (item.kind == JunkKind.FILE) scanned += f.path
            }
        }
        // Tell the gallery / music apps the files are gone
        if (scanned.isNotEmpty()) MediaScannerConnection.scanFile(context, scanned.toTypedArray(), null, null)
        count to freed
    }
}

/** Real used / free space and what it is used for. */
object StorageOverview {
    data class Result(val total: Long, val free: Long, val parts: List<Pair<String, Long>>, val limited: Boolean)

    suspend fun compute(context: Context, fullAccess: Boolean): Result = withContext(Dispatchers.IO) {
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        val total = st.totalBytes
        val free = st.availableBytes
        var videos = 0L; var music = 0L; var images = 0L; var docs = 0L; var apks = 0L
        runCatching {
            val proj = arrayOf(
                MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.DISPLAY_NAME,
            )
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val size = c.getLong(0)
                    val type = c.getInt(1)
                    val mime = c.getString(2)?.lowercase(Locale.ROOT) ?: ""
                    val name = c.getString(3)?.lowercase(Locale.ROOT) ?: ""
                    when {
                        type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> videos += size
                        type == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> music += size
                        type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> images += size
                        name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".xapk") ||
                            mime == "application/vnd.android.package-archive" -> apks += size
                        mime == "application/pdf" || mime.startsWith("text/") || mime.contains("document") ||
                            mime.contains("msword") || mime.contains("sheet") || mime.contains("presentation") ||
                            mime.contains("epub") -> docs += size
                    }
                }
            }
        }
        val used = (total - free).coerceAtLeast(0)
        val known = videos + music + images + docs + apks
        Result(
            total, free,
            listOf(
                "Videos" to videos, "Music & audio" to music, "Images" to images,
                "Documents" to docs, "APK files" to apks,
                "Apps, system & other" to (used - known).coerceAtLeast(0),
            ),
            limited = !fullAccess,
        )
    }
}
