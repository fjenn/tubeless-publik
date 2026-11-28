package app.tubeless.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tubeless.data.JsonFile
import app.tubeless.engine.AudioPick
import app.tubeless.engine.Caption
import app.tubeless.engine.Format
import app.tubeless.engine.Net
import app.tubeless.engine.Stream
import app.tubeless.engine.Sys
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import app.tubeless.engine.chaptersIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.CoreMedia.CMTimeRangeMake
import platform.AVFoundation.AVAssetDownloadDelegateProtocol
import platform.AVFoundation.AVAssetDownloadURLSession
import platform.AVFoundation.AVAssetExportPresetPassthrough
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataCommonKeyTitle
import platform.AVFoundation.AVMetadataItem
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetPreferPreciseDurationAndTimingKey
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.commonKey
import platform.AVFoundation.duration
import platform.AVFoundation.playable
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.stringValue
import platform.AVFoundation.timeRange
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLSessionConfiguration
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv
import kotlin.coroutines.coroutineContext

/** an environment variable (the E2E hooks in Offline.kt) */
@OptIn(ExperimentalForeignApi::class)
internal fun offlineEnv(name: String): String? = getenv(name)?.toKString()

/**
 * Videos saved on the iPhone and iPad to watch without internet, as plain files (as the desktop does): the
 * H.264 picture up to 1080p and the AAC sound in the language the player would choose (AudioPick, the original
 * unless you speak none of it) are fetched over HTTP ranges from the stream's own addresses, resumable, then
 * put together by AVFoundation (passthrough, no re-encoding) into one .mp4 that AVPlayer opens with a file
 * address: nothing about a saved video ever touches the network. A download counts only once that file opens,
 * plays, shows a picture and is as long as the video.
 *
 * Earlier builds saved Apple's HLS package (.movpkg, AVAssetDownloadURLSession). Those played parts of
 * themselves from YouTube and, in airplane mode, never started (TestFlight build 6); they show as "Download
 * again" in Downloads (one tap) and are fetched again as a file.
 *
 * Background: downloads run in the app (the queue is on disk and carries on from the bytes it has after a pause,
 * a lost connection or a restart). Leaving the app keeps them going under a background task, and on iOS 26+ under
 * the continued-processing task App.swift starts (its banner shows the progress). Saved files are kept out of
 * iCloud backups. Same members as the JVM Offline (common/jvm), so the shared screens use it unchanged.
 */
@OptIn(ExperimentalForeignApi::class)
class Offline(private val app: AppState) {
    private val io = CoroutineScope(SupervisorJob() + Sys.IO)
    private val index = JsonFile(app.platform.dataDir / "offline-ios.json", OfflineIndex.serializer(), OfflineIndex(), io)
    private val files = OfflineFiles(app.platform.dataDir, io)
    val saved: StateFlow<OfflineIndex> get() = index.state
    val prefs: JsonFile<DownloadPrefs> get() = files.prefs

    val progress = mutableStateMapOf<String, Float>()
    val pending = mutableStateMapOf<String, Video>()
    val stats = mutableStateMapOf<String, DlStat>()
    var asking by mutableStateOf<Video?>(null)
    /** the sound is the one the player would play (AudioPick); the dialog offers heights and captions */
    val pickTracks = false

    /** the desktop can keep downloads in a folder of its choosing; iOS keeps them in the app */
    val folder: String get() = ""
    suspend fun moveTo(path: String): Boolean = false

    private val jobs = HashMap<String, Job>()
    /** cancelled to be paused: the files stay */
    private val pausing = HashSet<String>()
    private val turn = Mutex()
    private var running = 0

    /** the network: on a metered path (mobile data, Low Data Mode) Wi-Fi-only downloads wait */
    private var expensive = false
    private var online = true
    private val monitor = nw_path_monitor_create().also { m ->
        nw_path_monitor_set_update_handler(m) { path ->
            expensive = nw_path_is_expensive(path) || nw_path_is_constrained(path)
            online = nw_path_get_status(path) == nw_path_status_satisfied
        }
        nw_path_monitor_set_queue(m, dispatch_get_main_queue())
        nw_path_monitor_start(m)
    }

    private var bgTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    init {
        dropOldSessions()
        oldPackages()
        // the queue left from the last run carries on where it stopped
        for (q in files.queue.value.items) {
            if (q.id in index.value.items) { files.drop(q.id); continue }
            pending[q.id] = q.video(); progress[q.id] = 0f
            stats[q.id] = stats[q.id] ?: DlStat(paused = q.paused)
            if (!q.paused) launch(q)
        }
        val nc = NSNotificationCenter.defaultCenter
        nc.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> enteredBackground() }
        nc.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> endBackgroundTask() }
        attachShared(app)
    }

    fun has(id: String) = id in index.value.items
    fun downloading(id: String) = id in progress
    fun queuedSmart(id: String) = files.get(id)?.smart == true
    fun metered() = expensive || !online

    private fun absolute(rel: String) = NSHomeDirectory() + "/" + rel
    private fun folderRel(id: String) = "$DIR/$id"
    private fun thumbRel(id: String) = "Library/Application Support/offline-extras/$id.jpg"

    fun thumbUrl(s: SavedVideo): String? = s.thumb.takeIf { it.isNotEmpty() }?.let { absolute(it) }
        ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }?.let { NSURL.fileURLWithPath(it).absoluteString }

    /** the heights there are in H.264 up to 1080p (what is saved), with the size with the sound */
    suspend fun choices(id: String): DownloadChoices {
        val s = YouTube.stream(id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        val vs = h264(s.video)
        if (vs.isEmpty()) error(tr("This video can't be downloaded"))
        val a = AudioPick.best(s.audio, app.playback.audioLangFor(s), mp4Only = true)
        val qs = vs.groupBy { it.side }.map { (h, fs) ->
            val best = fs.maxWith(compareBy<Format>({ it.fps }, { it.bitrate }))
            QualityOption(h, best.fps, listOf("h264"), best.contentLength.takeIf { it > 0 }?.plus(a?.contentLength ?: 0)
                ?: ((best.bitrate.toLong() + (a?.bitrate ?: 0)) * s.lengthSec / 8))
        }.sortedByDescending { it.height }
        return DownloadChoices(qs, emptyList(), s.captions)
    }

    /**
     * a saved video as a stream: the one .mp4 (its address in [Stream.hls], which is what IosPlayer opens; the
     * formats say what is in it). null when it isn't saved or its file is gone
     */
    fun stream(id: String): Stream? {
        val s = index.value.items[id] ?: return null
        if (!s.video.file.endsWith(".mp4")) return null
        val path = absolute(s.video.file)
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val url = NSURL.fileURLWithPath(path).absoluteString ?: return null
        val v = s.video
        val a = s.audio
        return Stream(
            id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, views = 0,
            description = s.description, keywords = emptyList(), live = false, hls = url,
            video = listOf(Format(v.itag, url, "video/mp4", v.codecs, v.width, v.height, v.fps, v.bitrate, v.size)),
            audio = listOf(Format(a.itag, url, "audio/mp4", a.codecs, bitrate = a.bitrate, sampleRate = a.sampleRate, channels = a.channels,
                audioTrack = a.track.ifEmpty { null }, audioTrackName = s.audioName.ifEmpty { null })),
            captions = s.allCaptions.filter { NSFileManager.defaultManager.fileExistsAtPath(absolute(it.file)) }
                .map { Caption(it.lang, it.name, NSURL.fileURLWithPath(absolute(it.file)).absoluteString ?: "", it.auto) },
            expiresAt = Long.MAX_VALUE, client = "offline",
        )
    }

    fun start(
        v: Video, maxHeight: Int, track: String? = null, caption: Caption? = null,
        codec: String? = null, captions: String? = null, smart: Boolean = false,
    ) {
        if (has(v.id) || downloading(v.id)) return
        val q = QueuedDownload(
            id = v.id, title = v.title, author = v.author, channelId = v.channelId, lengthSec = v.lengthSec, short = v.short,
            height = maxHeight, codec = "h264", track = track,
            captions = captions ?: caption?.let { it.lang + "|" + it.name }, smart = smart, at = Sys.nowMs() / 1000,
        )
        files.put(q)
        progress[v.id] = 0f; pending[v.id] = v; stats[v.id] = DlStat(waiting = tr("Queued"))
        if (!smart) app.toast(tr("Downloading: {t}").replace("{t}", v.title))
        OfflineEvents.log("download_queued", "videoId" to v.id, "height" to maxHeight, "smart" to smart)
        launch(q)
    }

    private fun launch(q: QueuedDownload) {
        jobs[q.id]?.cancel()
        jobs[q.id] = app.scope.launch {
            val t0 = Sys.nowMs()
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
                val got = download(files.get(q.id) ?: q)
                index.update { it.copy(items = it.items + (q.id to got)) }
                index.flushNow()
                files.drop(q.id)
                OfflineEvents.log("download_done", "videoId" to q.id, "height" to got.video.height, "fps" to got.video.fps,
                    "codec" to got.video.codecs, "audio" to got.audio.codecs, "audioLang" to got.audio.track, "bytes" to got.size,
                    "ms" to (Sys.nowMs() - t0), "file" to absolute(got.video.file), "captions" to got.allCaptions.size,
                    "chapters" to got.chapters.size, "thumb" to got.thumb.isNotEmpty(), "smart" to q.smart)
                if (!q.smart) app.toast(tr("Saved for offline: {t}").replace("{t}", got.title))
            } catch (e: CancellationException) {
                if (q.id in pausing) keep = true
                else withContext(NonCancellable) { deleteFolder(q.id); files.drop(q.id) }
                throw e
            } catch (e: Exception) {
                OfflineEvents.log("download_failed", "videoId" to q.id, "error" to (e.message ?: e.toString()))
                if (q.smart) { deleteFolder(q.id); files.drop(q.id) }
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
        deleteFolder(id)
    }

    /** the saved .mp4 is already one file of picture and sound; nothing to make */
    fun export(id: String, done: (String?) -> Unit) = done(null)

    fun remove(id: String) {
        val s = index.value.items[id] ?: run { if (downloading(id)) cancel(id); return }
        index.update { it.copy(items = it.items - id) }
        index.flushNow()
        removeFiles(s)
    }

    fun removeAll() {
        for (id in pending.keys.toList()) cancel(id)
        for (id in index.value.items.keys.toList()) remove(id)
    }

    private fun removeFiles(s: SavedVideo) {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(absolute(s.video.file), error = null)
        deleteFolder(s.id)
        if (s.thumb.isNotEmpty()) fm.removeItemAtPath(absolute(s.thumb), error = null)
        for (c in s.allCaptions) fm.removeItemAtPath(absolute(c.file), error = null)
    }

    private fun deleteFolder(id: String) {
        NSFileManager.defaultManager.removeItemAtPath(absolute(folderRel(id)), error = null)
    }

    // ---------------------------------------------------------------- earlier builds' HLS packages

    /**
     * Saved HLS packages (.movpkg, builds up to 6) go back into the queue, paused, as "Download again to watch
     * offline": one tap on it fetches the file. Their own "playable offline" flag was true for the packages that
     * then never started in airplane mode, so no package is trusted to play without the network.
     */
    private fun oldPackages() {
        val old = index.value.items.values.filter { !it.video.file.endsWith(".mp4") }
        if (old.isEmpty()) return
        index.update { it.copy(items = it.items - old.map { o -> o.id }.toSet()) }
        index.flushNow()
        for (s in old) {
            removeFiles(s)
            if (files.get(s.id) != null) continue
            files.put(QueuedDownload(
                id = s.id, title = s.title, author = s.author, channelId = s.channelId, lengthSec = s.lengthSec, short = s.short,
                height = s.video.height.takeIf { it > 0 } ?: 1080, codec = "h264", smart = s.smart, paused = true, at = s.at,
            ))
            stats[s.id] = DlStat(paused = true, waiting = tr("Download again to watch offline"))
            OfflineEvents.log("download_redo", "videoId" to s.id, "was" to s.video.file)
        }
    }

    /** the background sessions earlier builds left with iOS: whatever they still fetch is not wanted any more */
    private fun dropOldSessions() {
        for (id in listOf("dev.tubeless.app.downloads", "dev.tubeless.app.downloads.wifi")) runCatching {
            val s = AVAssetDownloadURLSession.sessionWithConfiguration(
                NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(id), assetDownloadDelegate = OldSession(),delegateQueue = NSOperationQueue.mainQueue)
            s.invalidateAndCancel()
        }
    }

    // ---------------------------------------------------------------- downloading

    /** H.264 video formats up to 1080p (the short side: an upright Short's 1080×1920 too), as YouTube Premium saves */
    private fun h264(list: List<Format>): List<Format> =
        list.filter { it.codecFamily == "h264" && it.height > 0 && it.side <= MAX_SIDE }

    /** "1080p" for 1920×1080 and for an upright 1080×1920 alike, as YouTube names them (the heights asked for here) */
    private val Format.side: Int get() = minOf(width.takeIf { it > 0 } ?: height, height)

    private fun pickVideo(list: List<Format>, maxHeight: Int): Format? {
        val usable = h264(list)
        val fit = usable.filter { it.side <= maxHeight }.ifEmpty { usable.sortedBy { it.side }.take(1) }
        val top = fit.maxOfOrNull { it.side } ?: return null
        return fit.filter { it.side == top }.maxWithOrNull(compareBy<Format>({ it.fps }, { it.bitrate }))
    }

    private suspend fun download(q: QueuedDownload): SavedVideo {
        val s = YouTube.stream(q.id)
        if (s.live) error(tr("Live streams can't be downloaded"))
        // a resumed download keeps the formats it started with (its partial files are of them)
        val video = s.video.firstOrNull { q.videoItag != 0 && it.itag == q.videoItag } ?: pickVideo(s.video, q.height)
            ?: error(tr("This video can't be downloaded"))
        // the sound the player would choose: the original, or the dub in your language (Playback.audioLangFor)
        val lang = q.track?.let { t -> s.audio.firstOrNull { it.audioTrack == t }?.audioLang } ?: app.playback.audioLangFor(s)
        val audio = s.audio.firstOrNull { q.audioItag != 0 && it.itag == q.audioItag && it.audioTrack == q.track && it.codecFamily == "aac" }
            ?: AudioPick.best(s.audio, lang, mp4Only = true) ?: error(tr("This video can't be downloaded"))
        val dir = folderRel(q.id)
        FileSystem.SYSTEM.createDirectories(absolute(dir).toPath())
        if (video.itag != q.videoItag || audio.itag != q.audioItag || audio.audioTrack != q.track) {
            withContext(Sys.IO) { FileSystem.SYSTEM.list(absolute(dir).toPath()).forEach { FileSystem.SYSTEM.delete(it) } }
            files.edit(q.id) { it.copy(videoItag = video.itag, audioItag = audio.itag, track = audio.audioTrack) }
        }
        val title = s.title.ifEmpty { q.title }
        pending[q.id]?.let { if (it.title != title) pending[q.id] = it.copy(title = title) }
        val (thumb, caps) = extras(q, s)
        val chapters = chaptersIn(s.description).map { SavedChapter(it.title, it.startMs) }
        OfflineEvents.log("download_formats", "videoId" to q.id, "video" to video.itag, "height" to video.height, "codec" to video.codecs,
            "audio" to audio.itag, "audioLang" to (audio.audioLang ?: ""), "audioName" to (audio.audioTrackName ?: ""),
            "tracks" to s.audio.mapNotNull { it.audioLang }.distinct().size, "client" to s.client)

        val vRel = "$dir/video.mp4"
        val aRel = "$dir/audio.m4a"
        val total = (video.contentLength + audio.contentLength).coerceAtLeast(1)
        var before = 0L
        val vSize = fetch(q.id, video, vRel) { got -> report(q.id, before + got, total) }
        before = vSize
        val aSize = fetch(q.id, audio, aRel) { got -> report(q.id, before + got, total) }
        stats[q.id] = DlStat(waiting = tr("Finishing…"))
        val outRel = "$dir/${q.id}.mp4"
        val length = s.lengthSec.takeIf { it > 0 } ?: q.lengthSec
        mux(absolute(vRel), absolute(aRel), absolute(outRel), audio.audioLang)
        val check = playable(absolute(outRel), length)
        if (check != null) {
            FileSystem.SYSTEM.delete(absolute(outRel).toPath(), mustExist = false)
            error(check)
        }
        FileSystem.SYSTEM.delete(absolute(vRel).toPath(), mustExist = false)
        FileSystem.SYSTEM.delete(absolute(aRel).toPath(), mustExist = false)
        val size = FileSystem.SYSTEM.metadataOrNull(absolute(outRel).toPath())?.size ?: (vSize + aSize)
        NSURL.fileURLWithPath(absolute(dir)).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        val original = AudioPick.originalLang(s.audio)
        return SavedVideo(
            id = q.id, title = title, author = s.author.ifEmpty { q.author },
            channelId = s.channelId.ifEmpty { q.channelId }, lengthSec = length,
            video = SavedFile(video.itag, "video/mp4", video.codecs + "," + audio.codecs, video.width, video.height, video.fps, video.bitrate, size, file = outRel),
            audio = SavedFile(audio.itag, "audio/mp4", audio.codecs, bitrate = audio.bitrate, sampleRate = audio.sampleRate, channels = audio.channels,
                file = outRel, track = audio.audioTrack ?: ""),
            at = Sys.nowMs() / 1000,
            audioName = if (audio.audioLang != null && original != null && !audio.audioLang.equals(original, true)) audio.audioTrackName ?: audio.audioLang ?: "" else "",
            caption = caps.firstOrNull(), captions = caps, chapters = chapters, description = s.description, thumb = thumb,
            smart = q.smart, short = q.short,
        )
    }

    /** the thumbnail and the captions beside the video (each optional: the video is saved without them) */
    private suspend fun extras(q: QueuedDownload, s: Stream): Pair<String, List<SavedCaption>> {
        FileSystem.SYSTEM.createDirectories(absolute("Library/Application Support/offline-extras").toPath())
        val thumb = runCatching {
            val rel = thumbRel(q.id)
            val have = FileSystem.SYSTEM.metadataOrNull(absolute(rel).toPath())?.size ?: 0
            if (have < 2000) {
                val data = ((if (q.short) listOf("oardefault") else emptyList()) + listOf("maxresdefault", "sddefault", "hqdefault")).firstNotNullOfOrNull { k ->
                    runCatching { Net.bytes("https://i.ytimg.com/vi/${q.id}/$k.jpg", emptyMap()) }.getOrNull()
                        ?.takeIf { it.first == 200 && it.second.size > 2000 }?.second
                } ?: error("no thumbnail")
                withContext(Sys.IO) { FileSystem.SYSTEM.write(absolute(rel).toPath()) { write(data) } }
            }
            rel
        }.getOrDefault("")
        val lang = app.store.settings.value.captionLang.ifEmpty { app.store.settings.value.lang.ifEmpty { "en" } }
        val want = q.captions ?: when (prefs.value.captions) { "all" -> "*"; "mine" -> "mine"; else -> "" }
        val list = when (want) {
            "" -> emptyList()
            "*" -> s.captions
            "mine" -> listOfNotNull(s.captions.firstOrNull { it.lang.substringBefore('-') == lang && !it.auto } ?: s.captions.firstOrNull { it.lang.substringBefore('-') == lang })
            else -> listOfNotNull(s.captions.firstOrNull { it.lang + "|" + it.name == want })
        }
        val caps = list.mapIndexedNotNull { i, c ->
            runCatching {
                val text = Net.get(YouTube.captionUrl(c))
                require("-->" in text)
                val rel = "Library/Application Support/offline-extras/${q.id}.$i.vtt"
                withContext(Sys.IO) { FileSystem.SYSTEM.write(absolute(rel).toPath()) { writeUtf8(text) } }
                SavedCaption(c.lang, c.name, rel, c.auto)
            }.getOrNull()
        }
        return thumb to caps
    }

    private class Speed(var at: Long, var bytes: Long, var bps: Long)
    private val speed = HashMap<String, Speed>()
    private var lastReport = 0L

    private fun report(id: String, done: Long, total: Long) {
        val now = Sys.nowMs()
        if (now - lastReport < 250 && done < total) return
        lastReport = now
        val s = speed.getOrPut(id) { Speed(now, done, 0) }
        if (now - s.at >= 1000) { s.bps = ((done - s.bytes) * 1000 / (now - s.at)).coerceAtLeast(0); s.at = now; s.bytes = done }
        if (id in progress) {
            // the last few percent are putting the file together and checking it
            progress[id] = (done.toFloat() / total * 0.98f).coerceIn(0f, 0.98f)
            stats[id] = DlStat(done, total, s.bps)
        }
    }

    /**
     * One stream file in pieces (one long request is slowed down by YouTube), carrying on from a partial file
     * left by a pause or a restart. An expired or refused link is asked for again; a dropped connection is tried
     * again with smaller pieces. Returns the size.
     */
    private suspend fun fetch(id: String, f: Format, outRel: String, progress: (Long) -> Unit): Long {
        val fs = FileSystem.SYSTEM
        val out = absolute(outRel).toPath()
        fs.metadataOrNull(out)?.size?.let { if (f.contentLength > 0 && it == f.contentLength) { progress(it); return it } }
        val part = (absolute(outRel) + ".part").toPath()
        var url = f.url
        var len = f.contentLength
        var have = fs.metadataOrNull(part)?.size ?: 0L
        if (len in 1..<have) { fs.delete(part); have = 0 }
        var renewed = 0
        var failures = 0
        var chunk = CHUNK
        progress(have)
        while (len <= 0 || have < len) {
            coroutineContext.ensureActive()
            val to = if (len > 0) minOf(len - 1, have + chunk - 1) else have + chunk - 1
            val (code, body) = try {
                Net.bytes(url, mapOf("Range" to "bytes=$have-$to"))
            } catch (e: okio.IOException) {
                if (++failures > 8) throw e
                chunk = maxOf(1L shl 20, chunk / 2)
                delay(1000L * failures * failures)
                continue
            }
            if (code == 403 || code == 410) {
                if (renewed++ >= 5) error("HTTP $code")
                url = YouTube.stream(id).let { s -> (s.video + s.audio).firstOrNull { it.itag == f.itag && it.audioTrack == f.audioTrack }?.url } ?: error("HTTP $code")
                continue
            }
            if (code != 200 && code != 206) {
                if (code in 500..599 && ++failures <= 8) { delay(1000L * failures); continue }
                error("HTTP $code")
            }
            failures = 0
            // the whole file instead of the rest: start over
            if (code == 200 && have > 0) { withContext(Sys.IO) { fs.delete(part) }; have = 0 }
            withContext(Sys.IO) {
                val sink = fs.appendingSink(part, mustExist = false).buffer()
                try { sink.write(body) } finally { sink.close() }
            }
            have += body.size
            progress(have)
            if (code == 200) { if (len <= 0) len = have; break }
            if (body.isEmpty() || (len <= 0 && body.size < to - (have - body.size) + 1)) { if (len <= 0) len = have; break }
        }
        if (len > 0 && have < len) error(tr("The download stopped early"))
        withContext(Sys.IO) { fs.delete(out, mustExist = false); fs.atomicMove(part, out) }
        return have
    }

    /**
     * The picture and the sound into one .mp4, as they are (passthrough: no re-encoding, a 1080p hour takes seconds).
     * The sound track is marked with its language.
     */
    private suspend fun mux(video: String, audio: String, out: String, lang: String?) {
        val opts = mapOf<Any?, Any>(AVURLAssetPreferPreciseDurationAndTimingKey to true)
        val va = AVURLAsset(uRL = NSURL.fileURLWithPath(video), options = opts)
        val aa = AVURLAsset(uRL = NSURL.fileURLWithPath(audio), options = opts)
        load(va, listOf("tracks", "duration")); load(aa, listOf("tracks", "duration"))
        val sv = va.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: error("the picture file has no video")
        val sa = aa.tracksWithMediaType(AVMediaTypeAudio).firstOrNull() as? AVAssetTrack ?: error("the sound file has no audio")
        val comp = AVMutableComposition()
        val vt = comp.addMutableTrackWithMediaType(AVMediaTypeVideo, preferredTrackID = 0) ?: error("no video track")
        val at = comp.addMutableTrackWithMediaType(AVMediaTypeAudio, preferredTrackID = 0) ?: error("no audio track")
        // AVFoundation reads YouTube's fragmented files as twice their length (the samples are right, the track's length
        // isn't: a saved 63 s Short came out 127 s long, the second half a frozen frame); each file's own index (sidx)
        // says how long it really is, and only that much is taken
        val vEnd = withContext(Sys.IO) { sidxEnd(video) }
        val aEnd = withContext(Sys.IO) { sidxEnd(audio) }
        fun range(t: AVAssetTrack, end: Pair<Long, Int>?) = t.timeRange.useContents {
            val whole = CMTimeGetSeconds(duration.readValue())
            val cut = end?.let { (v, scale) -> v.toDouble() / scale }
            if (cut == null || cut <= 0.0 || cut >= whole) t.timeRange
            else CMTimeRangeMake(CMTimeMake(0, 1), CMTimeMake(end.first, end.second))
        }
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            if (!vt.insertTimeRange(range(sv, vEnd), ofTrack = sv, atTime = CMTimeMake(0, 1), error = err.ptr))
                error("video: " + (err.value?.localizedDescription ?: "can't be read"))
            if (!at.insertTimeRange(range(sa, aEnd), ofTrack = sa, atTime = CMTimeMake(0, 1), error = err.ptr))
                error("audio: " + (err.value?.localizedDescription ?: "can't be read"))
        }
        vt.preferredTransform = sv.preferredTransform
        lang?.let { at.extendedLanguageTag = it }
        NSFileManager.defaultManager.removeItemAtPath(out, error = null)
        val ex = AVAssetExportSession(asset = comp, presetName = AVAssetExportPresetPassthrough) ?: error("can't put the file together")
        ex.outputURL = NSURL.fileURLWithPath(out)
        ex.outputFileType = AVFileTypeMPEG4
        // the index at the front: AVPlayer starts without reading to the end first
        ex.shouldOptimizeForNetworkUse = true
        suspendCancellableCoroutine<Unit> { c ->
            ex.exportAsynchronouslyWithCompletionHandler { if (c.isActive) c.resumeWith(Result.success(Unit)) }
            c.invokeOnCancellation { ex.cancelExport() }
        }
        if (ex.status != AVAssetExportSessionStatusCompleted) error("couldn't put the file together: " + (ex.error?.localizedDescription ?: "status ${ex.status}"))
    }

    /**
     * Where a fragmented MP4's media ends, from its segment index (sidx: earliest time + every subsegment's duration),
     * as (ticks, timescale); null when it has none
     */
    private fun sidxEnd(path: String): Pair<Long, Int>? = runCatching {
        FileSystem.SYSTEM.openReadOnly(path.toPath()).use { h ->
            val size = h.size()
            var at = 0L
            val head = ByteArray(16)
            fun u32(b: ByteArray, i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
                ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
            // the top-level boxes up to the first fragment: ftyp, moov, sidx
            while (at + 8 <= size) {
                if (h.read(at, head, 0, 16) < 8) return@use null
                var len = u32(head, 0)
                val type = head.decodeToString(4, 8)
                if (len == 1L) len = (u32(head, 8) shl 32) or u32(head, 12)
                if (len < 8) return@use null
                if (type == "moof" || type == "mdat") return@use null
                if (type == "sidx") {
                    val b = ByteArray(minOf(len, 1L shl 22).toInt())
                    h.read(at, b, 0, b.size)
                    val version = b[8].toInt()
                    val scale = u32(b, 16).toInt()
                    var i = 20
                    val earliest = if (version == 0) u32(b, i).also { i += 8 } else ((u32(b, i) shl 32) or u32(b, i + 4)).also { i += 16 }
                    val count = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
                    i += 4
                    var total = 0L
                    for (n in 0 until count) { if (i + 12 > b.size) break; total += u32(b, i + 4); i += 12 }
                    return@use if (scale > 0 && total > 0) (earliest + total) to scale else null
                }
                at += len
            }
            null
        }
    }.getOrNull()

    private suspend fun load(a: AVURLAsset, keys: List<String>) {
        withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<Unit> { c -> a.loadValuesAsynchronouslyForKeys(keys) { if (c.isActive) c.resumeWith(Result.success(Unit)) } }
        } ?: error("the file can't be read")
    }

    /**
     * null when the saved file opens and plays without anything else: AVFoundation calls it playable, it has a
     * picture and a sound, its first frame decodes and it is as long as the video ([lengthSec], ±2 s or 2 %);
     * else why not
     */
    private suspend fun playable(path: String, lengthSec: Int): String? {
        val a = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        load(a, listOf("playable", "tracks", "duration"))
        if (!a.playable) return tr("The download is incomplete") + " (not playable)"
        if (a.tracksWithMediaType(AVMediaTypeVideo).isEmpty() || a.tracksWithMediaType(AVMediaTypeAudio).isEmpty()) return tr("The download is incomplete") + " (tracks)"
        val dur = CMTimeGetSeconds(a.duration)
        val tolerance = maxOf(2.0, lengthSec * 0.02)
        if (!(dur > 0) || lengthSec > 0 && kotlin.math.abs(dur - lengthSec) > tolerance) return tr("The download is incomplete") + " (${dur.toInt()} s of $lengthSec s)"
        val frame = withContext(Sys.IO) {
            val g = AVAssetImageGenerator(asset = a)
            g.copyCGImageAtTime(CMTimeMake(minOf(1000L, (dur * 500).toLong()), 1000), actualTime = null, error = null)
        }
        if (frame == null) return tr("The download is incomplete") + " (no picture)"
        platform.CoreGraphics.CGImageRelease(frame)
        OfflineEvents.log("download_checked", "file" to path, "durationSec" to dur, "expectedSec" to lengthSec)
        return null
    }

    // ---------------------------------------------------------------- background

    /** leaving the app with downloads under way: keep running as long as iOS allows */
    private fun enteredBackground() {
        if (pending.isEmpty() || bgTask != UIBackgroundTaskInvalid) return
        bgTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("Tubeless downloads") { endBackgroundTask() }
    }

    private fun endBackgroundTask() {
        if (bgTask == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(bgTask)
        bgTask = UIBackgroundTaskInvalid
    }

    companion object {
        /** where the saved files are, relative to the app's home folder (its absolute path changes with updates) */
        private const val DIR = "Library/Application Support/offline"
        private const val CHUNK = 8L shl 20
        /** YouTube Premium's highest download quality */
        private const val MAX_SIDE = 1080
    }
}

/** the delegate earlier builds' download sessions are made again with, only to cancel them */
@OptIn(ExperimentalForeignApi::class)
private class OldSession : NSObject(), AVAssetDownloadDelegateProtocol

/**
 * An HLS sound's name in YouTube's list ("American English - original", "Español - dubbed"): AVFoundation keeps
 * the list's NAME as the option's title (its displayName is only the language)
 */
fun hlsName(o: AVMediaSelectionOption): String =
    o.commonMetadata.filterIsInstance<AVMetadataItem>().firstOrNull { it.commonKey == AVMetadataCommonKeyTitle }?.stringValue ?: o.displayName

/**
 * Of an HLS list's sounds, the one in [lang] (a stream's audioLang), else the original: by the stream's formats,
 * else by YouTube's name for it. null: none of these is known. YouTube's lists mark no sound as the default, so
 * AVFoundation left alone plays the first one, an Arabic dub (the list is in alphabetical order).
 */
fun audioOption(options: List<AVMediaSelectionOption>, lang: String?, audio: List<Format>): AVMediaSelectionOption? {
    fun of(l: String?) = l?.let { options.firstOrNull { o -> o.extendedLanguageTag.equals(l, ignoreCase = true) } }
    return of(lang) ?: of(AudioPick.originalLang(audio)) ?: options.firstOrNull { hlsName(it).endsWith("original", ignoreCase = true) }
}
