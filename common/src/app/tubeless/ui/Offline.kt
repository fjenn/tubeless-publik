package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.data.JsonFile
import app.tubeless.engine.Caption
import app.tubeless.engine.Format
import app.tubeless.engine.Sys
import app.tubeless.engine.Video
import app.tubeless.engine.oneDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/*
 * Downloads, the part every device shares (S6): what a saved video is, the download settings, the
 * quality question, the Downloads page and the settings card. The downloader itself is per platform
 * (common/jvm OfflineJvm.kt: DASH files over HTTP ranges; common/ios OfflineIos.kt: H.264 + AAC over
 * HTTP ranges, put together into one .mp4); both expose the same members, so these screens use either.
 *
 * No artificial limits: any quality the video has (up to 8K, AV1/VP9/H.264), downloads never expire,
 * no device or count limit, as many at once as the settings say.
 */

/** One downloaded file: the format it came from (with its index ranges) and where it lies. */
@Serializable
data class SavedFile(
    val itag: Int,
    val mime: String,
    val codecs: String,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val size: Long = 0,
    val init: List<Long>? = null,
    val index: List<Long>? = null,
    val sampleRate: Int = 0,
    val channels: Int = 2,
    /** relative to the offline folder */
    val file: String,
    /** the sound's track ("en-US.4", a dub's or the original's) when the video has several; "" otherwise */
    val track: String = "",
)

/** captions saved with a video, cleaned WebVTT ([file] relative to the offline folder) */
@Serializable
data class SavedCaption(val lang: String, val name: String, val file: String, val auto: Boolean = false)

@Serializable
data class SavedChapter(val title: String, val startMs: Long)

@Serializable
data class SavedVideo(
    val id: String,
    val title: String,
    val author: String = "",
    val channelId: String = "",
    val lengthSec: Int = 0,
    val video: SavedFile,
    val audio: SavedFile,
    val at: Long = 0,
    /** the audio track's name when it isn't the original (a dub, audio description) */
    val audioName: String = "",
    /** the first caption track (older downloads have only this one) */
    val caption: SavedCaption? = null,
    /** every caption track kept with it */
    val captions: List<SavedCaption> = emptyList(),
    val chapters: List<SavedChapter> = emptyList(),
    /** the video's description (its chapters and links work offline) */
    val description: String = "",
    /** the thumbnail, relative to the offline folder ("" = none) */
    val thumb: String = "",
    /** put there by Smart downloads (replaced by newer ones when watched) */
    val smart: Boolean = false,
    val short: Boolean = false,
) {
    val size: Long get() = video.size + audio.size
    fun asVideo() = Video(id, title, author, channelId, lengthSec, short = short)
    val allCaptions: List<SavedCaption> get() = captions.ifEmpty { listOfNotNull(caption) }
}

@Serializable
data class OfflineIndex(val items: Map<String, SavedVideo> = emptyMap())

/** The download settings (Settings → Downloads); kept beside the index, per device. */
@Serializable
data class DownloadPrefs(
    /** [ASK] each time, [BEST] the highest the video has, else the highest height up to this */
    val quality: Int = ASK,
    /** "auto" (H.264 where the height has it, else VP9, else AV1), "av1", "vp9", "h264" */
    val codec: String = "auto",
    /** captions kept with each download: "off", "mine" (the caption language), "all" */
    val captions: String = "mine",
    /** downloads wait while the connection is metered (mobile data) */
    val wifiOnly: Boolean = false,
    /** how many download at the same time */
    val parallel: Int = 3,
    /** Smart downloads: new videos (and Shorts) put there by themselves, on Wi-Fi */
    val smart: Boolean = false,
    val smartCount: Int = 10,
    val smartShorts: Boolean = true,
    val smartQuality: Int = 1080,
    /** the most smart downloads may take, in MB; 0 = no limit */
    val smartCapMb: Long = 0,
    /** smart downloads you removed or watched: not downloaded again */
    val smartDone: List<String> = emptyList(),
) {
    companion object {
        const val ASK = 0
        const val BEST = 100_000
    }
}

/** A download asked for and not finished: kept on disk, so the queue carries on after a restart. */
@Serializable
data class QueuedDownload(
    val id: String,
    val title: String,
    val author: String = "",
    val channelId: String = "",
    val lengthSec: Int = 0,
    val short: Boolean = false,
    val height: Int,
    val codec: String = "auto",
    val track: String? = null,
    /** captions: null = from the settings; "" = none; "*" = all; else "lang|name" */
    val captions: String? = null,
    val smart: Boolean = false,
    val paused: Boolean = false,
    val at: Long = 0,
    /** the formats picked when it started: a resumed download continues the same files */
    val videoItag: Int = 0,
    val audioItag: Int = 0,
) {
    fun video() = Video(id, title, author, channelId, lengthSec, short = short)
}

@Serializable
data class DownloadQueue(val items: List<QueuedDownload> = emptyList())

/** a download under way: bytes so far, of how many, how fast, and why it waits (when it does) */
data class DlStat(val done: Long = 0, val total: Long = 0, val bps: Long = 0, val paused: Boolean = false, val waiting: String = "")

/** one quality the video can be saved in */
data class QualityOption(val height: Int, val fps: Int, val codecs: List<String>, val bytes: Long, val hdr: Boolean = false)

/** what the download dialog offers for a video */
data class DownloadChoices(
    val qualities: List<QualityOption>,
    val tracks: List<Pair<String, String>> = emptyList(),
    val captions: List<Caption> = emptyList(),
)

/** the settings and queue files, the same on every platform */
class OfflineFiles(dir: Path, scope: CoroutineScope) {
    val prefs = JsonFile(dir / "downloads-settings.json", DownloadPrefs.serializer(), DownloadPrefs(), scope)
    val queue = JsonFile(dir / "downloads-queue.json", DownloadQueue.serializer(), DownloadQueue(), scope)

    fun put(q: QueuedDownload) { queue.update { d -> d.copy(items = d.items.filter { it.id != q.id } + q) }; queue.flushNow() }
    fun drop(id: String) { queue.update { d -> d.copy(items = d.items.filter { it.id != id }) }; queue.flushNow() }
    fun edit(id: String, f: (QueuedDownload) -> QueuedDownload) { queue.update { d -> d.copy(items = d.items.map { if (it.id == id) f(it) else it }) }; queue.flushNow() }
    fun get(id: String) = queue.value.items.firstOrNull { it.id == id }
}

/** "123 MB", "1.4 GB" */
fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> oneDecimal(bytes / (1024.0 * 1024 * 1024)) + " GB"
    else -> "${(bytes shr 20).coerceAtLeast(1)} MB"
}

/** "2160p60 HDR" */
fun QualityOption.label(): String = "${height}p" + (if (fps > 30) "$fps" else "") + (if (hdr) " HDR" else "")

fun dlHeightName(h: Int): String = when {
    h >= 4320 -> "8K"
    h >= 2160 -> "4K"
    h >= 1440 -> "2K"
    h >= 1080 -> "Full HD"
    h >= 720 -> "HD"
    else -> ""
}

fun dlCodecName(c: String): String = when (c) {
    "av1" -> "AV1"; "vp9" -> "VP9"; "h264" -> "H.264"; else -> tr("Most compatible")
}

/** a video's audio tracks to pick from: (track id, name), the original first; empty when it has one */
fun audioChoices(audio: List<Format>): List<Pair<String, String>> {
    val out = audio.filter { !it.drc && it.audioTrack != null }.distinctBy { it.audioTrack }.sortedBy { if (it.audioDefault) 0 else 1 }
        .map { f -> f.audioTrack!! to (f.audioTrackName ?: f.audioLang?.let { Sys.languageName(it, L.lang) } ?: f.audioTrack) }
    return if (out.size > 1) out else emptyList()
}

/**
 * The video formats a download would pick from: the highest height up to [maxHeight] (the lowest
 * there is when none fits); at that height [codec] when it has it, else H.264 (plays everywhere),
 * VP9, AV1; then the higher frame rate and bitrate.
 */
fun pickVideoFormat(list: List<Format>, maxHeight: Int, codec: String = "auto"): Format? {
    val usable = list.filter { it.indexRange != null && it.height > 0 }
    val fit = usable.filter { it.height <= maxHeight }.ifEmpty { usable.sortedBy { it.height }.take(1) }
    val top = fit.maxOfOrNull { it.height } ?: return null
    val rank = mapOf("h264" to 0, "vp9" to 1, "av1" to 2)
    return fit.filter { it.height == top }
        .sortedWith(compareBy<Format>({ if (it.codecFamily == codec) -1 else rank[it.codecFamily] ?: 3 }, { -it.fps }, { -it.bitrate })).firstOrNull()
}

/**
 * the original-language audio (or the [track] asked for: a dub, audio description), in the
 * container that goes with the video (AAC with MP4, Opus with WebM)
 */
fun pickAudioFormat(list: List<Format>, video: Format, track: String? = null): Format? {
    val usable = list.filter { it.indexRange != null && !it.drc }
    val main = usable.filter { track != null && it.audioTrack == track }
        .ifEmpty { usable.filter { it.audioDefault } }.ifEmpty { usable }
    val want = if (video.mime.endsWith("webm")) "webm" else "mp4"
    return (main.filter { it.mime.endsWith(want) }.ifEmpty { main }).maxByOrNull { it.bitrate }
}

/** every height a DASH stream has, with its codecs and the size with the sound */
fun qualitiesOf(video: List<Format>, audio: List<Format>): List<QualityOption> {
    val usable = video.filter { it.indexRange != null && it.height > 0 }
    return usable.groupBy { it.height }.map { (h, fs) ->
        val best = pickVideoFormat(fs, h)!!
        val a = pickAudioFormat(audio, best)
        QualityOption(h, fs.maxOf { it.fps }, fs.map { it.codecFamily }.distinct().sortedBy { listOf("h264", "vp9", "av1").indexOf(it) },
            best.contentLength + (a?.contentLength ?: 0), fs.any { it.codecs.startsWith("vp09.02") || it.codecs.startsWith("av01.0.") && it.codecs.contains(".10.") })
    }.sortedByDescending { it.height }
}

/**
 * The app's E2E event log (scripts/e2e-m3.sh): with TUBELESS_E2E_LOG=<file> set, one JSON object a
 * line is appended for downloads and offline playback.
 */
object OfflineEvents {
    private val file: String? by lazy { offlineEnv("TUBELESS_E2E_LOG")?.ifEmpty { null } }

    fun log(event: String, vararg kv: Pair<String, Any?>) {
        // one writer per log: where the platform turned the E2E log on (Android: e2e.jsonl in its data folder,
        // no environment variable) the events go there, under its lock
        if (!E2E.path.isNullOrEmpty()) { E2E.log(event, *kv.filter { it.second != null }.toTypedArray()); return }
        val f = file ?: return
        val line = buildJsonObject {
            put("event", JsonPrimitive(event))
            put("t", JsonPrimitive(Sys.nowMs()))
            for ((k, v) in kv) when (v) {
                null -> {}
                is Number -> put(k, JsonPrimitive(v))
                is Boolean -> put(k, JsonPrimitive(v))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }.toString()
        runCatching { val sink = Sys.fs.appendingSink(f.toPath(), mustExist = false).buffer(); try { sink.writeUtf8(line + "\n") } finally { sink.close() } }
    }
}

/**
 * Called once when the downloader starts (both platforms): the settings card, Smart downloads, the
 * offline-play event and the E2E hooks.
 */
internal fun Offline.attachShared(app: AppState) {
    registerDownloadSettings(app)
    SmartDownloads.attach(app)
    if (!playHooked) {
        playHooked = true
        Playback.onStart += { _, v, s ->
            if (s.client == "offline") OfflineEvents.log("play_offline", "videoId" to v.id,
                "height" to (s.video.firstOrNull()?.height ?: app.offline.saved.value.items[v.id]?.video?.height ?: 0),
                "codec" to s.video.firstOrNull()?.codecFamily, "file" to (s.video.firstOrNull()?.url ?: s.hls))
        }
    }
    // E2E hooks (scripts/e2e-m3.sh and the S6 check), all off unless the variable is set:
    // TUBELESS_E2E_DOWNLOAD="id@height[:codec][,…]" queues those downloads at start and shows Downloads;
    // TUBELESS_E2E_ASK=id opens the quality question for it; TUBELESS_E2E_EXPORT=1 saves each finished
    // one as one file too; TUBELESS_E2E_PLAY_OFFLINE=id opens that saved video once it is there
    val queued = offlineEnv("TUBELESS_E2E_DOWNLOAD")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    if (queued.isNotEmpty()) app.scope.launch {
        kotlinx.coroutines.delay(3000)
        for (spec in queued) {
            val id = spec.substringBefore('@').trim()
            val rest = spec.substringAfter('@', "")
            val h = rest.substringBefore(':').toIntOrNull() ?: DownloadPrefs.BEST
            val codec = rest.substringAfter(':', "auto").ifEmpty { "auto" }
            if (!has(id) && !downloading(id)) start(Video(id, id), h, codec = codec)
            else if (stats[id]?.paused == true) resume(id)
        }
        app.go(Screen.Downloads)
    }
    offlineEnv("TUBELESS_E2E_ASK")?.ifEmpty { null }?.let { id ->
        app.scope.launch { kotlinx.coroutines.delay(3000); asking = Video(id, id) }
    }
    if (offlineEnv("TUBELESS_E2E_EXPORT") == "1") app.scope.launch {
        val done = HashSet<String>()
        saved.collect { idx ->
            for (id in idx.items.keys) if (done.add(id)) export(id) { p -> OfflineEvents.log("export_result", "videoId" to id, "file" to p) }
        }
    }
    // several ids ("a,b,c"): each is played in turn, then sought to the middle (offline_seek: where it got to and whether it plays)
    offlineEnv("TUBELESS_E2E_PLAY_OFFLINE")?.ifEmpty { null }?.split(',')?.filter { it.isNotBlank() }?.let { ids ->
        app.scope.launch {
            for ((i, id) in ids.withIndex()) {
                while (!has(id)) kotlinx.coroutines.delay(1000)
                kotlinx.coroutines.delay(1500)
                if (i == 0) { app.go(Screen.Downloads); kotlinx.coroutines.delay(6000) }
                saved.value.items[id]?.let { app.watch(it.asVideo()) }
                if (ids.size == 1) break
                kotlinx.coroutines.delay(10_000)
                val st = app.player.state.value
                val to = (st.durationMs.takeIf { it > 0 } ?: (saved.value.items[id]?.lengthSec ?: 0) * 1000L) / 2
                val t0 = Sys.nowMs()
                app.player.seekTo(to)
                var moved = -1L
                while (Sys.nowMs() - t0 < 15_000) {
                    kotlinx.coroutines.delay(100)
                    val s = app.player.state.value
                    if (s.playing && !s.buffering && s.positionMs > to + 300) { moved = Sys.nowMs() - t0; break }
                }
                kotlinx.coroutines.delay(3000)
                val s = app.player.state.value
                OfflineEvents.log("offline_seek", "videoId" to id, "toMs" to to, "positionMs" to s.positionMs, "durationMs" to s.durationMs,
                    "playing" to s.playing, "playsAfterMs" to moved, "audio" to s.audioTrack, "height" to s.nowHeight, "error" to s.error)
            }
        }
    }
}

private var playHooked = false

// ------------------------------------------------------------------ the quality question

/** a label with the choice beside it; the choice opens a list */
@Composable
private fun Choice(label: String, now: String, options: List<String>, pick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(now, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; pick(i) }) }
            }
        }
    }
}

/**
 * Download quality, as YouTube asks it but without its caps: every height the video has (up to 8K),
 * each with its size, the format (AV1, VP9, H.264), the audio track and the captions to keep, and
 * "Remember my settings". With a remembered quality (Settings → Downloads) it starts at once.
 */
@Composable
fun OfflineDialog(app: AppState, v: Video) {
    val prefs by app.offline.prefs.state.collectAsState()
    if (prefs.quality != DownloadPrefs.ASK) {
        LaunchedEffect(v.id) {
            app.offline.asking = null
            app.offline.start(v, prefs.quality, codec = prefs.codec)
        }
        return
    }
    var choices by remember { mutableStateOf<DownloadChoices?>(null) }
    var failed by remember { mutableStateOf("") }
    LaunchedEffect(v.id) {
        try { choices = app.offline.choices(v.id) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failed = e.message ?: e.toString()
        }
    }
    var pick by remember { mutableStateOf(-1) }
    var codec by remember { mutableStateOf(prefs.codec) }
    var track by remember { mutableStateOf(0) }
    // captions: -1 off, -2 all, else the index
    var cap by remember { mutableStateOf(-1) }
    var remember by remember { mutableStateOf(false) }
    val qs = choices?.qualities.orEmpty()
    LaunchedEffect(choices) {
        val c = choices ?: return@LaunchedEffect
        // the best there is, as nothing is capped (a lower one is a tap away)
        pick = 0
        val want = app.store.settings.value.captionLang.ifEmpty { L.lang }
        cap = when (prefs.captions) {
            "all" -> if (c.captions.isEmpty()) -1 else -2
            "mine" -> c.captions.indexOfFirst { it.lang.substringBefore('-') == want && !it.auto }
            else -> -1
        }
    }
    AlertDialog(
        onDismissRequest = { app.offline.asking = null },
        title = { Text(tr("Download quality")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp))
                when {
                    failed.isNotEmpty() -> Text(tr("Couldn't download: {e}").replace("{e}", failed), color = MaterialTheme.colorScheme.error)
                    choices == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(tr("Looking up the qualities..."), modifier = Modifier.padding(start = 12.dp))
                    }
                    else -> {
                        qs.forEachIndexed { i, q ->
                            Row(Modifier.fillMaxWidth().clickable { pick = i }.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(pick == i, onClick = { pick = i })
                                Column(Modifier.weight(1f)) {
                                    Text(q.label() + dlHeightName(q.height).let { if (it.isEmpty()) "" else "  ·  $it" }, fontWeight = if (pick == i) FontWeight.SemiBold else null)
                                    Text(q.codecs.joinToString(" / ") { dlCodecName(it) }, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (q.bytes > 0) Text(sizeText(q.bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val here = qs.getOrNull(pick)?.codecs.orEmpty()
                        if (here.size > 1) {
                            val opts = listOf("auto") + here
                            Choice(tr("Format"), dlCodecName(if (codec in here) codec else "auto"), opts.map { dlCodecName(it) }) { codec = opts[it] }
                        }
                        val c = choices!!
                        if (c.tracks.isNotEmpty()) Choice(tr("Audio track"), c.tracks.getOrNull(track)?.second ?: "", c.tracks.map { it.second }) { track = it }
                        if (c.captions.isNotEmpty()) Choice(tr("Subtitles"),
                            when (cap) { -1 -> tr("Off"); -2 -> tr("All languages"); else -> c.captions.getOrNull(cap)?.name ?: tr("Off") },
                            listOf(tr("Off"), tr("All languages")) + c.captions.map { it.name }) { cap = if (it == 0) -1 else if (it == 1) -2 else it - 2 }
                        Row(Modifier.fillMaxWidth().clickable { remember = !remember }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(remember, onCheckedChange = { remember = it })
                            Text(tr("Remember my settings"), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(tr("Downloads never expire. Change this any time in Settings → Downloads."), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        Text(tr("For your own viewing only. Sharing or re-uploading it is your own responsibility."), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = qs.isNotEmpty() && pick >= 0, onClick = {
                val q = qs[pick]
                val h = if (pick == 0) DownloadPrefs.BEST else q.height
                val c = choices!!
                val useCodec = if (codec in q.codecs) codec else "auto"
                app.store.settings.update { it.copy(offlineHeight = h) }
                if (remember) app.offline.prefs.update { it.copy(quality = h, codec = useCodec) }
                app.offline.asking = null
                val caps = when (cap) { -1 -> ""; -2 -> "*"; else -> c.captions.getOrNull(cap)?.let { it.lang + "|" + it.name } ?: "" }
                app.offline.start(v, h, c.tracks.getOrNull(track)?.takeIf { track > 0 }?.first, null, codec = useCodec, captions = caps)
            }) { Text(tr("Download")) }
        },
        dismissButton = { TextButton(onClick = { app.offline.asking = null }) { Text(tr("Cancel")) } },
    )
}

// ------------------------------------------------------------------ the Downloads page

@Composable
private fun DownloadFolder(app: AppState) {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(app.offline.folder) }
    var note by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Download folder"), style = MaterialTheme.typography.titleSmall)
            Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = { app.platform.openFolder(folder) }) { Text(tr("Open")) }
        TextButton(onClick = {
            app.platform.pickFolder(folder) { picked ->
                if (picked != null) scope.launch {
                    note = tr("Moving...")
                    val ok = app.offline.moveTo(picked)
                    folder = app.offline.folder
                    note = if (ok) "" else if (app.offline.progress.isNotEmpty()) tr("Wait until the downloads finish, then try again.")
                        else tr("Tubeless can't write to that folder.")
                }
            }
        }) { Text(tr("Change")) }
    }
}

/** a download under way: title, bar, "120 MB of 1.2 GB · 5 MB/s", pause / resume and cancel */
@Composable
private fun QueueRow(app: AppState, v: Video) {
    val st = app.offline.stats[v.id] ?: DlStat()
    val p = app.offline.progress[v.id] ?: 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) { Img(v.thumb, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            val words = when {
                st.paused -> st.waiting.ifEmpty { tr("Paused") }
                st.waiting.isNotEmpty() -> st.waiting
                else -> listOfNotNull(
                    "${(p * 100).toInt()}%",
                    if (st.total > 0) sizeText(st.done) + " " + tr("of") + " " + sizeText(st.total) else null,
                    if (st.bps > 0) sizeText(st.bps) + "/s" else null,
                // a line breaks only between the parts, never inside "7 MB/s"
                ).joinToString(" · ") { it.replace(' ', '\u00A0') }
            }
            Text(words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { if (st.paused) app.offline.resume(v.id) else app.offline.pause(v.id) }) {
            Icon(if (st.paused) Sym.PlayArrow.outline else Sym.Pause.outline, if (st.paused) tr("Resume") else tr("Pause"))
        }
        IconButton(onClick = { app.offline.cancel(v.id) }) { Icon(Sym.Close.outline, tr("Cancel")) }
    }
}

/** a saved video: its own thumbnail (from the device, so it shows offline too) and what was saved */
@Composable
private fun SavedRow(app: AppState, s: SavedVideo, all: List<Video>, onNote: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { app.watch(s.asVideo(), all) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.width(168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Img(app.offline.thumbUrl(s) ?: s.asVideo().thumb, Modifier.fillMaxSize())
            if (s.lengthSec > 0) Text(duration(s.lengthSec), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    .background(androidx.compose.ui.graphics.Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.author.isNotEmpty()) Text(s.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                listOfNotNull(
                    "${s.video.height}p" + (if (s.video.fps > 30) "${s.video.fps}" else ""),
                    s.video.codecs.takeIf { it.isNotEmpty() }?.let { dlCodecName(Format(0, "", "", it).codecFamily) },
                    s.audioName.ifEmpty { null },
                    s.allCaptions.size.takeIf { it > 0 }?.let { if (it == 1) s.allCaptions[0].name else tr("{n} subtitles").replace("{n}", "$it") },
                    if (s.chapters.isNotEmpty()) tr("{n} chapters").replace("{n}", "${s.chapters.size}") else null,
                    sizeText(s.size),
                    if (s.smart) tr("Smart download") else null,
                ).joinToString(" · ") { it.replace(' ', '\u00A0') },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Sym.MoreVert.outline, null) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(tr("Remove download")) }, onClick = { menu = false; app.offline.remove(s.id) })
                // the web: the joined file goes to the browser's own downloads (OfflineWeb.export)
                if (app.platform.canPickFolder || app.platform.name == "web") DropdownMenuItem(text = { Text(tr("Save as one file")) }, onClick = {
                    menu = false
                    onNote(tr("Saving as one file..."))
                    app.offline.export(s.id) { p -> onNote(if (p != null) tr("Saved: ") + p else tr("Couldn't put it into one file.")) }
                })
            }
        }
    }
}

/** Library → Downloads: what is being saved, and what is saved (it plays without internet). */
@Composable
fun DownloadsScreen(app: AppState) {
    val saved by app.offline.saved.collectAsState()
    val prefs by app.offline.prefs.state.collectAsState()
    val items = saved.items.values.sortedByDescending { it.at }
    val videos = items.map { it.asVideo() }
    var note by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("Downloads"), Modifier.weight(1f))
                OutlinedButton(onClick = { app.go(Screen.SettingsPage("downloads")) }) {
                    Icon(Sym.Settings.outline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Settings"))
                }
            }
            Text(
                if (items.isEmpty()) tr("Videos you download play here without internet.")
                else tr("{n} videos · {s} on this device").let { t -> if (items.size == 1 && t.startsWith("{n} videos")) t.replace("{n} videos", "1 video") else t }
                    .replace("{n}", "${items.size}").replace("{s}", sizeText(items.sumOf { it.size })),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            // Smart downloads, as YouTube shows it at the top of Downloads
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).clickable { app.offline.prefs.update { it.copy(smart = !it.smart) } }
                .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.DownloadForOffline.outline, null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(tr("Smart downloads"), style = MaterialTheme.typography.titleSmall)
                    Text(if (prefs.smart) SmartDownloads.status.ifEmpty { tr("Videos and Shorts picked for you are saved on Wi-Fi") }
                        else tr("Off: turn on to have videos and Shorts for you saved on Wi-Fi"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(prefs.smart, onCheckedChange = { b -> app.offline.prefs.update { it.copy(smart = b) } },
                    colors = androidx.compose.material3.SwitchDefaults.colors(uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
            }
            // desktop: where the files are, and a button to keep them somewhere else
            if (app.platform.canPickFolder) Box(Modifier.padding(top = 8.dp)) { DownloadFolder(app) }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
            if (app.offline.pending.isNotEmpty()) Text(tr("Downloading"), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }
        items(app.offline.pending.values.toList(), key = { "p" + it.id }) { v -> QueueRow(app, v) }
        if (items.isEmpty() && app.offline.pending.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Sym.Download.outline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Videos you download will appear here"), modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(items, key = { "s" + it.id }) { s -> SavedRow(app, s, videos) { note = it } }
    }
}

// ------------------------------------------------------------------ Settings → Downloads

@Composable
private fun DlRow(title: String, sub: String = "", onClick: (() -> Unit)? = null, enabled: Boolean = true, end: @Composable () -> Unit = {}) {
    // off (its parent switch is off): greyed and not clickable, as the other settings
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
        .alpha(if (enabled) 1f else 0.38f)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
        end()
    }
}

@Composable
private fun DlToggle(title: String, sub: String, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) =
    SettingsToggle(title, sub, on, enabled, set)

@Composable
private fun <T> DlChoice(title: String, sub: String, value: T, options: List<Pair<T, String>>, enabled: Boolean = true, pick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    DlRow(title, sub, onClick = { open = true }, enabled = enabled) {
        Box {
            val c = MaterialTheme.colorScheme
            Row(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh).border(1.dp, c.outline, RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == value }?.second ?: "$value", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Icon(Sym.KeyboardArrowDown.outline, null, Modifier.padding(start = 6.dp).size(18.dp), tint = c.onSurfaceVariant)
            }
            DropdownMenu(open, { open = false }) {
                for ((k, label) in options) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; pick(k) })
            }
        }
    }
}

private val HEIGHTS = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
private fun heightOptions() = HEIGHTS.map { it to ("${it}p" + dlHeightName(it).let { n -> if (n.isEmpty()) "" else " ($n)" }) }

private var registered = false

/** the "Downloads" card in Settings (Hooks.kt SettingsSections) */
internal fun registerDownloadSettings(app: AppState) {
    if (registered) return
    registered = true
    fun prefs() = app.offline.prefs
    val rows = listOf(
        SettingsSections.Row("quality resolution 4k 8k 1080p ask") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Download quality"), tr("Any quality the video has, up to 8K"), p.quality,
                listOf(DownloadPrefs.ASK to tr("Ask each time"), DownloadPrefs.BEST to tr("Best available")) + heightOptions()) { q -> prefs().update { it.copy(quality = q) } }
        },
        SettingsSections.Row("format codec av1 vp9 h264") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Preferred format"), tr("Used when the quality has more than one"), p.codec,
                listOf("auto", "av1", "vp9", "h264").map { it to dlCodecName(it) }) { c -> prefs().update { it.copy(codec = c) } }
        },
        SettingsSections.Row("subtitles captions") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Subtitles"), tr("Kept with each download, and put into the file when you save it as one file"), p.captions,
                listOf("off" to tr("Off"), "mine" to tr("My language"), "all" to tr("All languages"))) { c -> prefs().update { it.copy(captions = c) } }
        },
        SettingsSections.Row("wifi mobile data cellular metered") {
            val p by prefs().state.collectAsState()
            DlToggle(tr("Download over Wi-Fi only"), tr("Downloads wait while you're on mobile data"), p.wifiOnly) { b -> prefs().update { it.copy(wifiOnly = b) } }
        },
        SettingsSections.Row("parallel simultaneous at once") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Downloads at the same time"), "", p.parallel, (1..8).map { it to "$it" }) { n -> prefs().update { it.copy(parallel = n) } }
        },
        SettingsSections.Row("smart downloads recommended shorts automatic") {
            val p by prefs().state.collectAsState()
            DlToggle(tr("Smart downloads"), tr("New videos and Shorts from your Home and Watch later are saved by themselves on Wi-Fi; watched ones make room for new ones"),
                p.smart) { b -> prefs().update { it.copy(smart = b) } }
        },
        SettingsSections.Row("smart downloads how many count") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Smart downloads: how many"), "", p.smartCount, listOf(5, 10, 20, 30, 50, 100).map { it to "$it" }, enabled = p.smart) { n -> prefs().update { it.copy(smartCount = n) } }
        },
        SettingsSections.Row("smart downloads shorts") {
            val p by prefs().state.collectAsState()
            // greyed while smart downloads are off: it only matters with them on
            DlToggle(tr("Smart downloads: include Shorts"), "", p.smartShorts, enabled = p.smart) { b -> prefs().update { it.copy(smartShorts = b) } }
        },
        SettingsSections.Row("smart downloads quality") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Smart downloads: quality"), "", p.smartQuality, listOf(DownloadPrefs.BEST to tr("Best available")) + heightOptions(), enabled = p.smart) { q -> prefs().update { it.copy(smartQuality = q) } }
        },
        SettingsSections.Row("smart downloads storage limit space") {
            val p by prefs().state.collectAsState()
            DlChoice(tr("Smart downloads: storage"), tr("The most space smart downloads may take"), p.smartCapMb,
                listOf(0L to tr("No limit")) + listOf(1L, 2, 5, 10, 20, 50, 100).map { it * 1024 to "$it GB" }, enabled = p.smart) { mb -> prefs().update { it.copy(smartCapMb = mb) } }
        },
        SettingsSections.Row("delete all downloads remove storage") {
            var ask by remember { mutableStateOf(false) }
            val saved by app.offline.saved.collectAsState()
            DlRow(tr("Delete all downloads"), tr("{n} videos · {s}").replace("{n}", "${saved.items.size}").replace("{s}", sizeText(saved.items.values.sumOf { it.size })),
                onClick = { ask = true })
            if (ask) AlertDialog(
                onDismissRequest = { ask = false },
                title = { Text(tr("Delete all downloads?")) },
                text = { Text(tr("The videos are removed from this device.")) },
                confirmButton = { TextButton(onClick = { ask = false; app.offline.removeAll() }) { Text(tr("Delete")) } },
                dismissButton = { TextButton(onClick = { ask = false }) { Text(tr("Cancel")) } },
            )
        },
        SettingsSections.Row("expire limit devices") {
            DlRow(tr("No limits"), tr("Downloads never expire, play without internet for as long as you keep them, and there's no limit on devices or on how many you keep."))
        },
    )
    SettingsSections.add(SettingsSections.Section("downloads", Sym.Download, "Downloads", "Quality, smart downloads, storage", rows))
}
