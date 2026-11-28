package app.tubeless.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubeless.data.JsonFile
import app.tubeless.engine.Caption
import app.tubeless.engine.Format
import app.tubeless.engine.Net
import app.tubeless.engine.Stream
import app.tubeless.engine.Video
import app.tubeless.engine.Vtt
import app.tubeless.engine.YouTube
import app.tubeless.engine.chaptersIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

// The downloader, Java's way (Android and the desktop): files and ranged requests through OkHttp.
// iOS saves Apple's HLS package instead; its Offline is in common/ios.

/** an environment variable (the E2E hooks in Offline.kt) */
/** the XDG Videos folder: $XDG_VIDEOS_DIR, else its line in ~/.config/user-dirs.dirs, else ~/Videos (null: no home) */
internal fun linuxVideosDir(): File? {
    val home = System.getProperty("user.home")?.takeIf { it.isNotEmpty() } ?: return null
    fun expand(v: String) = v.trim().trim('"').replace("\$HOME", home).replace("\${HOME}", home)
    offlineEnv("XDG_VIDEOS_DIR")?.takeIf { it.isNotBlank() }?.let { return File(expand(it)) }
    val cfg = File(offlineEnv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "user-dirs.dirs")
    runCatching { cfg.readLines() }.getOrNull()?.firstOrNull { it.trim().startsWith("XDG_VIDEOS_DIR=") }?.let { l ->
        val v = expand(l.substringAfter('='))
        // "$HOME/" alone means the folder is switched off: the home itself is no place for videos
        if (v.isNotEmpty() && File(v).absolutePath.trimEnd('/') != home.trimEnd('/')) return File(v)
    }
    return File(home, "Videos")
}

/** an environment variable */
internal fun offlineEnv(name: String): String? = System.getenv(name)

/** the same format, pointing at the file: the players play it exactly as they play YouTube's */
fun SavedFile.format(f: File) = Format(
    itag = itag, url = f.toURI().toString(), mime = mime, codecs = codecs, width = width, height = height, fps = fps,
    bitrate = bitrate, contentLength = size, initRange = init?.let { it[0]..it[1] }, indexRange = index?.let { it[0]..it[1] },
    sampleRate = sampleRate, channels = channels,
)

/**
 * Videos saved on this device to watch without internet. The video and the audio are kept as
 * YouTube serves them (two files with their index), so no conversion is needed: Android's player
 * reads them through the same DASH description it uses online, the desktop's mpv opens them directly.
 * Any quality the video has (8K, AV1/VP9/H.264); they never expire. The queue is kept on disk and
 * a download carries on from the bytes it has, after a pause, a lost connection or a restart.
 */
class Offline(private val app: AppState) {
    /** the app's own folder: the list of saved videos always stays here */
    private val home = File(app.platform.dataDir.toString(), "offline").apply { mkdirs() }
    /** where the videos' files are: the folder picked in Downloads, or the default one */
    private val dir: File get() = app.store.settings.value.offlineDir.ifEmpty { null }?.let(::File) ?: defaultDir
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val index = JsonFile(File(home, "index.json").toOkioPath(), OfflineIndex.serializer(), OfflineIndex(), io)
    /**
     * Where new videos go unless a folder was picked. Linux: the user's Videos folder (XDG_VIDEOS_DIR, else ~/Videos)
     * in "Tubeless", as a desktop user expects, not a hidden config folder. Elsewhere, and on a Linux computer that
     * already keeps saved videos in the app's own folder (an older build), the app's own folder, so they all still play.
     */
    private val defaultDir: File by lazy {
        val linux = app.platform.name == "desktop" && System.getProperty("os.name").orEmpty().startsWith("Linux")
        val old = (index.value.items.keys + files.queue.value.items.map { it.id }).any { File(home, it).exists() }
        if (!linux || old) home else linuxVideosDir()?.let { File(it, "Tubeless") } ?: home
    }
    private val files = OfflineFiles(home.toOkioPath(), io)
    val folder: String get() = dir.absolutePath
    /** Settings → Downloads */
    val prefs: JsonFile<DownloadPrefs> get() = files.prefs

    /**
     * Moves the saved videos to [path] and keeps new ones there. Waits for no download: false
     * while one is under way, or when the folder can't be written; then nothing has moved.
     */
    suspend fun moveTo(path: String): Boolean = withContext(Dispatchers.IO) {
        if (progress.isNotEmpty()) return@withContext false
        val from = dir
        val to = File(path)
        if (to.canonicalPath == from.canonicalPath) return@withContext true
        if (!to.isDirectory && !to.mkdirs() || !to.canWrite()) return@withContext false
        val moved = ArrayList<String>()
        fun move(a: File, b: File) = a.renameTo(b) || runCatching { a.copyRecursively(b, overwrite = true) }.isSuccess && a.deleteRecursively()
        for (id in index.value.items.keys) {
            val a = File(from, id)
            if (!a.exists()) continue
            if (move(a, File(to, id))) moved += id
            else {
                // put back what already went, so every video still plays from where the list says
                File(to, id).deleteRecursively()
                for (m in moved) move(File(to, m), File(from, m))
                return@withContext false
            }
        }
        app.store.settings.update { it.copy(offlineDir = if (to.canonicalPath == defaultDir.canonicalPath) "" else to.absolutePath) }
        true
    }
    val saved: StateFlow<OfflineIndex> get() = index.state

    /** downloads under way (or paused): video id → done 0..1 */
    val progress = mutableStateMapOf<String, Float>()
    /** what the downloads under way are (for the Downloads page) */
    val pending = mutableStateMapOf<String, Video>()
    /** bytes, speed and state of each download under way */
    val stats = mutableStateMapOf<String, DlStat>()
    /** the quality question is open for this video */
    var asking by mutableStateOf<Video?>(null)
    /** the download dialog can offer the audio tracks and captions */
    val pickTracks = true

    private val jobs = HashMap<String, Job>()
    /** cancelled to be paused: the files stay */
    private val pausing = HashSet<String>()
    private val turn = Mutex()
    private var running = 0

    init {
        // the queue left from the last run carries on where it stopped
        for (q in files.queue.value.items) {
            if (q.id in index.value.items) { files.drop(q.id); continue }
            pending[q.id] = q.video(); progress[q.id] = 0f; stats[q.id] = DlStat(paused = q.paused)
            if (!q.paused) launch(q)
        }
        attachShared(app)
    }

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress
    fun queuedSmart(id: String) = files.get(id)?.smart == true
    fun metered() = app.platform.metered()

    /** a saved video's thumbnail on this device, for the Downloads page (null: none saved) */
    fun thumbUrl(s: SavedVideo): String? = s.thumb.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile }?.toURI()?.toString()

    /** what the download dialog offers: every quality the video has, its audio tracks and captions */
    suspend fun choices(id: String): DownloadChoices {
        val s = YouTube.stream(id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        return DownloadChoices(qualitiesOf(s.video, s.audio), audioChoices(s.audio), s.captions)
    }

    /** a saved video as a stream for the player; null when it isn't saved (or its files are gone) */
    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        val vf = File(dir, s.video.file)
        val af = File(dir, s.audio.file)
        if (!vf.isFile || !af.isFile) return null
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = s.description, keywords = emptyList(), live = false, hls = null,
            video = listOf(s.video.format(vf)), audio = listOf(s.audio.format(af)),
            captions = s.allCaptions.mapNotNull { c -> File(dir, c.file).takeIf { it.isFile }?.let { Caption(c.lang, c.name, it.toURI().toString(), c.auto) } },
            expiresAt = Long.MAX_VALUE, client = "offline",
        )
    }

    /**
     * Queues [v] at the highest height up to [maxHeight] ([DownloadPrefs.BEST]: the highest it has).
     * [track] = an audio track other than the original; [caption] or [captions] (see
     * [QueuedDownload.captions]) = the captions to keep, null: as the settings say.
     */
    fun start(
        v: Video, maxHeight: Int, track: String? = null, caption: Caption? = null,
        codec: String? = null, captions: String? = null, smart: Boolean = false,
    ) {
        if (has(v.id) || downloading(v.id)) return
        val q = QueuedDownload(
            id = v.id, title = v.title, author = v.author, channelId = v.channelId, lengthSec = v.lengthSec, short = v.short,
            height = maxHeight, codec = codec ?: prefs.value.codec, track = track,
            captions = captions ?: caption?.let { it.lang + "|" + it.name }, smart = smart, at = System.currentTimeMillis() / 1000,
        )
        files.put(q)
        progress[v.id] = 0f; pending[v.id] = v; stats[v.id] = DlStat(waiting = tr("Queued"))
        if (!smart) app.toast(tr("Downloading: {t}").replace("{t}", v.title))
        OfflineEvents.log("download_queued", "videoId" to v.id, "height" to maxHeight, "codec" to q.codec, "smart" to smart)
        launch(q)
    }

    private fun launch(q: QueuedDownload) {
        jobs[q.id]?.cancel()
        jobs[q.id] = app.scope.launch {
            val t0 = System.currentTimeMillis()
            var mine = false
            var keep = false
            try {
                // its turn: as many at once as the settings say; on Wi-Fi when it has to be
                while (true) {
                    val p = prefs.value
                    if ((p.wifiOnly || q.smart) && metered()) { stats[q.id] = DlStat(waiting = tr("Waiting for Wi-Fi")); delay(15_000); continue }
                    if (turn.withLock { if (running < p.parallel.coerceIn(1, 16)) { running++; true } else false }) { mine = true; break }
                    if (stats[q.id]?.waiting.isNullOrEmpty()) stats[q.id] = DlStat(waiting = tr("Queued"))
                    delay(500)
                }
                stats[q.id] = DlStat()
                val got = withContext(Dispatchers.IO) { download(files.get(q.id) ?: q) }
                index.update { it.copy(items = it.items + (q.id to got)) }
                index.flushNow()
                files.drop(q.id)
                OfflineEvents.log("download_done", "videoId" to q.id, "height" to got.video.height, "fps" to got.video.fps,
                    "codec" to Format(0, "", "", got.video.codecs).codecFamily, "audio" to got.audio.codecs, "bytes" to got.size,
                    "ms" to (System.currentTimeMillis() - t0), "file" to File(dir, got.video.file).absolutePath,
                    "captions" to got.allCaptions.size, "chapters" to got.chapters.size, "thumb" to got.thumb.isNotEmpty(), "smart" to q.smart)
                if (!q.smart) app.toast(tr("Saved for offline: {t}").replace("{t}", got.title))
            } catch (e: CancellationException) {
                if (q.id in pausing) keep = true
                else withContext(Dispatchers.IO + NonCancellable) { File(dir, q.id).deleteRecursively(); files.drop(q.id) }
                throw e
            } catch (e: Exception) {
                OfflineEvents.log("download_failed", "videoId" to q.id, "error" to (e.message ?: e.toString()))
                if (q.smart) withContext(Dispatchers.IO) { File(dir, q.id).deleteRecursively(); files.drop(q.id) }
                else {
                    // kept, paused: Resume carries on from the bytes it has
                    keep = true
                    files.edit(q.id) { it.copy(paused = true) }
                    stats[q.id] = DlStat(paused = true, waiting = tr("Stopped: {e}").replace("{e}", e.message ?: e.toString()))
                    app.toast(tr("Couldn't download: {e}").replace("{e}", e.message ?: e.toString()))
                }
            } finally {
                if (mine) withContext(NonCancellable) { turn.withLock { running-- } }
                pausing.remove(q.id)
                if (jobs[q.id] === coroutineContext[Job]) jobs.remove(q.id)
                if (!keep) { progress.remove(q.id); pending.remove(q.id); stats.remove(q.id); speed.remove(q.id) }
            }
        }
    }

    /** stops a download and keeps what it has; [resume] carries on from there */
    fun pause(id: String) {
        files.edit(id) { it.copy(paused = true) }
        stats[id] = (stats[id] ?: DlStat()).copy(paused = true, bps = 0, waiting = "")
        jobs[id]?.let { pausing += id; it.cancel() }
    }

    fun resume(id: String) {
        val q = files.get(id) ?: return
        files.edit(id) { it.copy(paused = false) }
        stats[id] = DlStat(waiting = tr("Queued"))
        launch(q.copy(paused = false))
    }

    fun cancel(id: String) {
        val j = jobs[id]
        if (j != null) { pausing -= id; j.cancel(); return }
        // paused: nothing runs, so it goes here
        files.drop(id)
        progress.remove(id); pending.remove(id); stats.remove(id)
        io.launch { File(dir, id).deleteRecursively() }
    }

    /** the picture and the sound put together into one file next to the downloads, with the
     * subtitles, chapters and thumbnail in it; [done] gets its path, or null */
    fun export(id: String, done: (String?) -> Unit) {
        val s = index.value.items[id] ?: return done(null)
        io.launch {
            done(runCatching {
                val name = s.title.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { id }
                val extras = Mux.Extras(
                    title = s.title, artist = s.author,
                    captions = s.allCaptions.mapNotNull { c -> File(dir, c.file).takeIf { it.isFile }?.let { Mux.Sub(c.lang, c.name, it) } },
                    chapters = s.chapters.map { it.title to it.startMs },
                    cover = s.thumb.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.isFile },
                )
                val webm = s.video.file.endsWith(".webm")
                val ext = if (!webm) "mp4" else if (extras.isEmpty()) "webm" else "mkv"
                val out = File(dir, "$name.$ext")
                Mux.join(File(dir, s.video.file), File(dir, s.audio.file), out, extras)
                OfflineEvents.log("download_exported", "videoId" to id, "file" to out.absolutePath, "bytes" to out.length())
                out.absolutePath
            }.onFailure { it.printStackTrace() }.getOrNull())
        }
    }

    fun remove(id: String) {
        if (id !in index.value.items) { if (downloading(id)) cancel(id); return }
        index.update { it.copy(items = it.items - id) }
        io.launch { File(dir, id).deleteRecursively() }
    }

    /** Settings → Downloads → Delete all downloads: the saved ones and the ones under way */
    fun removeAll() {
        for (id in pending.keys.toList()) cancel(id)
        val ids = index.value.items.keys.toList()
        index.update { OfflineIndex() }
        io.launch { for (id in ids) File(dir, id).deleteRecursively() }
    }

    // ------------------------------------------------------------------ downloading

    private suspend fun download(q: QueuedDownload): SavedVideo {
        val s = YouTube.stream(q.id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        // a resumed download keeps the formats it started with (its partial files are of them)
        val video = s.video.firstOrNull { q.videoItag != 0 && it.itag == q.videoItag && it.indexRange != null }
            ?: pickVideoFormat(s.video, q.height, q.codec) ?: error(tr("This video can't be downloaded"))
        val audio = s.audio.firstOrNull { q.audioItag != 0 && it.itag == q.audioItag && it.indexRange != null }
            ?: pickAudioFormat(s.audio, video, q.track) ?: error(tr("This video can't be downloaded"))
        val folder = File(dir, q.id).apply { mkdirs() }
        if (video.itag != q.videoItag || audio.itag != q.audioItag) {
            folder.listFiles()?.forEach { it.delete() }
            files.edit(q.id) { it.copy(videoItag = video.itag, audioItag = audio.itag) }
        }
        val title = s.title.ifEmpty { q.title }
        app.scope.launch { pending[q.id]?.let { if (it.title != title) pending[q.id] = it.copy(title = title) } }
        // the captions, cleaned as the player shows them; without them the video is still saved
        val caps = captionsFor(s, q.captions).mapIndexedNotNull { i, c ->
            runCatching {
                val text = Vtt.clean(Net.get(YouTube.captionUrl(c)))
                require("-->" in text)
                val name = "${q.id}/captions.$i.${c.lang.replace(Regex("[^A-Za-z0-9-]"), "_")}${if (c.auto) ".auto" else ""}.vtt"
                File(dir, name).writeText(text)
                SavedCaption(c.lang, c.name, name, c.auto)
            }.getOrNull()
        }
        // the thumbnail: the largest there is, so it shows offline and goes into the one-file copy
        val thumb = runCatching {
            val name = "${q.id}/thumb.jpg"
            val f = File(dir, name)
            if (!f.isFile || f.length() == 0L) {
                val bytes = ((if (q.short) listOf("oardefault") else emptyList()) + listOf("maxresdefault", "sddefault", "hqdefault")).firstNotNullOfOrNull { k ->
                    Net.client.newCall(Request.Builder().url("https://i.ytimg.com/vi/${q.id}/$k.jpg").build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size > 2000 } else null
                    }
                } ?: error("no thumbnail")
                f.writeBytes(bytes)
            }
            name
        }.getOrDefault("")
        val chapters = chaptersIn(s.description).map { SavedChapter(it.title, it.startMs) }

        val vName = "${q.id}/video.${ext(video)}"
        val aName = "${q.id}/audio.${ext(audio)}"
        val total = (video.contentLength + audio.contentLength).coerceAtLeast(1)
        var before = 0L
        val vSize = fetch(q.id, video, File(dir, vName)) { got -> report(q.id, before + got, total) }
        before = vSize
        val aSize = fetch(q.id, audio, File(dir, aName)) { got -> report(q.id, before + got, total) }
        if (!folder.isDirectory) error("folder gone")
        return SavedVideo(
            id = q.id, title = title, author = s.author.ifEmpty { q.author },
            channelId = s.channelId.ifEmpty { q.channelId }, lengthSec = s.lengthSec.takeIf { it > 0 } ?: q.lengthSec,
            video = saved(video, vSize, vName), audio = saved(audio, aSize, aName), at = System.currentTimeMillis() / 1000,
            audioName = if (q.track != null && audio.audioTrack == q.track) audio.audioTrackName ?: audio.audioLang ?: "" else "",
            caption = caps.firstOrNull(), captions = caps, chapters = chapters, description = s.description, thumb = thumb,
            smart = q.smart, short = q.short,
        )
    }

    /** the caption tracks a download keeps: [want] as in [QueuedDownload.captions] */
    private fun captionsFor(s: Stream, want: String?): List<Caption> {
        val lang = app.store.settings.value.captionLang.ifEmpty { app.store.settings.value.lang.ifEmpty { "en" } }
        fun mine() = listOfNotNull(
            s.captions.firstOrNull { it.lang.substringBefore('-') == lang && !it.auto } ?: s.captions.firstOrNull { it.lang.substringBefore('-') == lang })
        return when (want) {
            null -> when (prefs.value.captions) { "all" -> s.captions; "mine" -> mine(); else -> emptyList() }
            "" -> emptyList()
            "*" -> s.captions
            else -> {
                val (l, n) = want.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                listOfNotNull(s.captions.firstOrNull { it.lang == l && it.name == n } ?: s.captions.firstOrNull { it.lang == l })
            }
        }
    }

    private class Speed(var at: Long, var bytes: Long, var bps: Long)
    private val speed = HashMap<String, Speed>()
    private var lastReport = 0L

    private fun report(id: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastReport < 250 && done < total) return
        lastReport = now
        val sp = synchronized(speed) {
            val s = speed.getOrPut(id) { Speed(now, done, 0) }
            if (now - s.at >= 1000) { s.bps = ((done - s.bytes) * 1000 / (now - s.at)).coerceAtLeast(0); s.at = now; s.bytes = done }
            s.bps
        }
        app.scope.launch {
            if (id in progress) {
                progress[id] = (done.toFloat() / total).coerceIn(0f, 1f)
                stats[id] = DlStat(done, total, sp)
            }
        }
    }

    /**
     * Downloads one stream file in 10 MB pieces (one long request is slowed down by YouTube),
     * carrying on from a partial file left by a pause or a restart. When the link expires or is
     * refused part way, a fresh one is asked for; a dropped connection is tried again. Returns the size.
     */
    private suspend fun fetch(id: String, f: Format, out: File, progress: (Long) -> Unit): Long {
        if (out.isFile && f.contentLength > 0 && out.length() == f.contentLength) { progress(out.length()); return out.length() }
        val part = File(out.path + ".part")
        var url = f.url
        var len = f.contentLength
        var have = if (part.isFile) part.length() else 0L
        if (len in 1..<have) { part.delete(); have = 0 }
        var renewed = 0
        var failures = 0
        progress(have)
        var sink = FileOutputStream(part, true)
        try {
            while (len <= 0 || have < len) {
                coroutineContext.ensureActive()
                val to = if (len > 0) minOf(len - 1, have + CHUNK - 1) else have + CHUNK - 1
                var got = 0L
                var code = 0
                try {
                    val r = Net.client.newCall(Request.Builder().url(url).header("Range", "bytes=$have-$to").build()).execute()
                    code = r.code
                    if (code == 403 || code == 410) {
                        r.close()
                        if (renewed++ >= 5) error("HTTP $code")
                        url = YouTube.stream(id).let { s -> (s.video + s.audio).firstOrNull { it.itag == f.itag }?.url } ?: error("HTTP $code")
                        continue
                    }
                    r.use {
                        if (code != 200 && code != 206) error("HTTP $code")
                        if (code == 200 && have > 0) {
                            // the server sent the whole file instead of the rest: start the file over
                            sink.close(); sink = FileOutputStream(part, false); have = 0
                        }
                        if (len <= 0) len = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: r.body?.contentLength()?.takeIf { code == 200 && it > 0 } ?: 0
                        val input = r.body?.byteStream() ?: error("empty")
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            got += n
                            progress(have + got)
                        }
                    }
                } catch (e: IOException) {
                    // a dropped connection: what came is kept, the rest asked for again (with longer waits)
                    have += got; sink.flush()
                    if (++failures > 8) throw e
                    delay(1000L * failures * failures)
                    continue
                }
                failures = 0
                have += got
                // a whole file in one answer, or nothing more to come: done
                if (code == 200 || got == 0L) break
            }
        } finally { sink.close() }
        if (len > 0 && have < len) error(tr("The download stopped early"))
        if (!part.renameTo(out)) { out.delete(); part.renameTo(out) }
        return have
    }

    private fun saved(f: Format, size: Long, name: String) = SavedFile(
        itag = f.itag, mime = f.mime, codecs = f.codecs, width = f.width, height = f.height, fps = f.fps, bitrate = f.bitrate,
        size = size, init = f.initRange?.let { listOf(it.first, it.last) }, index = f.indexRange?.let { listOf(it.first, it.last) },
        sampleRate = f.sampleRate, channels = f.channels, file = name,
    )

    private fun ext(f: Format) = if (f.mime.endsWith("webm")) "webm" else if (f.isVideo) "mp4" else "m4a"

    companion object {
        private const val CHUNK = 10L shl 20

        /** the highest height up to [maxHeight]; at that height H.264 first (plays everywhere), then VP9, AV1 */
        fun pickVideo(list: List<Format>, maxHeight: Int, codec: String = "auto"): Format? = pickVideoFormat(list, maxHeight, codec)

        fun pickAudio(list: List<Format>, video: Format, track: String? = null): Format? = pickAudioFormat(list, video, track)
    }
}
