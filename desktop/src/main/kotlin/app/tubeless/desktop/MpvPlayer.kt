package app.tubeless.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import app.tubeless.engine.Quality
import app.tubeless.engine.Caption
import app.tubeless.engine.Format
import app.tubeless.engine.Net
import app.tubeless.engine.ServerVideo
import app.tubeless.engine.Stream
import app.tubeless.engine.YouTube
import app.tubeless.ui.PlayOptions
import app.tubeless.ui.PlayerState
import app.tubeless.ui.VideoPlayer
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.Bitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * mpv (libmpv) as the desktop player. mpv plays YouTube's separate video and audio streams
 * together (the audio as an external track) and decodes on the GPU; through its render API it
 * draws each frame, subtitles included, into memory, and Compose paints that frame like any
 * image. So there is no native window inside the app: the controls (PlayerControls.kt), menus
 * and the mini player sit on top of the video exactly as on Android.
 *
 * YouTube gives one URL per quality, so changing quality reloads at the same position.
 */
class MpvPlayer : VideoPlayer {
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = st

    private val lib = LibMpv.load()
    private var h: Pointer? = null
    private val lock = Any()

    private var stream: Stream? = null
    private var opts = PlayOptions()
    private var pinned = 0
    /** the audio language picked for this video; null = the original */
    private var audioLang: String? = null
    private var caption: Caption? = null
    private var translateTo: String? = null
    /** the subtitle track added to the file (shown or not): the captions button only flips its visibility */
    @Volatile private var loadedCaption: Caption? = null
    @Volatile private var loadedTranslate: String? = null
    /** the audio stream added to the video (null: none, as for a live stream), and whether it was added again after failing */
    @Volatile private var audioUrl: String? = null
    @Volatile private var audioRetried = false
    /** polls in a row that found the same sound problem (it must last a second before it's shown) */
    private var soundBad = 0

    val available get() = lib != null

    // ------------------------------------------------------------------ the picture
    /** where mpv draws, at the current size */
    private class Target(val w: Int, val h: Int) {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(w, h, ColorType.RGB_888X, ColorAlphaType.OPAQUE), (w * 4 + 63) / 64 * 64)
        }
        val addr = Pointer(bitmap.peekPixels()!!.addr)
    }

    private var render: Pointer? = null
    private val renderLock = Any()
    private val wake = Semaphore(0)
    // kept in a field: JNA must not lose the callback while mpv holds it
    private val onUpdate = LibMpv.UpdateCallback { if (wake.availablePermits() == 0) wake.release() }
    private val apiType = LibMpv.cString("sw")
    // R G B and a byte mpv leaves at 0: Skia's RGB_888X never reads that byte (as alpha it would make the picture see-through)
    private val pixelFormat = LibMpv.cString("rgb0")
    private val swSize = Memory(8)
    private val stride = Memory(8)
    private var target: Target? = null
    /**
     * The frame on screen: a copy made once per frame and freed by hand when the next one
     * replaces it. (Drawing the bitmap itself made Skia copy it on every repaint into native
     * memory the garbage collector barely sees, and after a few minutes the app ran out of it.)
     */
    @Volatile private var front: Image? = null
    /**
     * Frames replaced but not freed yet (they are freed on the UI thread). When the app is in the
     * background, macOS slows that thread down (App Nap) while mpv goes on at 30-60 frames a second,
     * each a few MB: they piled up until the computer ran out of memory. Past a few, frames are dropped.
     */
    private val unfreed = java.util.concurrent.atomic.AtomicInteger(0)
    // Mitchell cubic: the last stretch to the view is sharp, without the softness of plain linear filtering
    private val sampling = org.jetbrains.skia.CubicResampler(1f / 3, 1f / 3)
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    /** bumped for every new frame: the Canvas reads it, so Compose repaints (only repaints) */
    private val frames = mutableIntStateOf(0)
    @Volatile private var view = IntSize.Zero
    @Volatile private var views = 0
    @Volatile private var closing = false
    /** the view changed size: draw the current frame again even when paused */
    @Volatile private var redraw = false
    /** frames drawn, frames mpv had ready that were not drawn (the UI thread behind), time spent drawing (PlayProbe) */
    @Volatile var framesDrawn = 0L
    @Volatile var framesSkipped = 0L
    @Volatile var drawNanos = 0L
    /** the size the last frame was drawn at */
    @Volatile var drawSize = 0 to 0
    @Volatile private var skippedAtLoad = 0L

    private fun mpv(): Pointer? {
        synchronized(lock) {
            h?.let { return it }
            if (closing) return null
            val l = lib ?: return null
            LibMpv.cNumbers()
            val p = l.mpv_create() ?: run { LibMpv.wontStart = true; return null }
            fun o(k: String, v: String) = l.mpv_set_option_string(p, k, v)
            o("vo", "libmpv")
            if (o("hwdec", "auto-copy-safe") < 0) o("hwdec", "auto-copy")   // GPU decoding, frames copied back for us
            o("idle", "yes"); o("keep-open", "no"); o("ytdl", "no")
            o("input-default-bindings", "no"); o("input-vo-keyboard", "no"); o("osc", "no")
            // read ahead (5 minutes, up to 150 MB, mpv's own limit) and keep the last 50 MB for seeking back.
            // It was 500 + 100 MB: all of it sits in memory, and the app took 1.5 GB on a long video (#87)
            o("cache", "yes"); o("cache-secs", "300"); o("demuxer-readahead-secs", "300")
            // S2: 64 + 24 MB (was 150 + 50): 1080p VP9 still has 2-3 minutes ahead (refilled in seconds), and the app
            // stays under 700 MB at 1080p
            o("demuxer-max-bytes", "64MiB"); o("demuxer-max-back-bytes", "24MiB")
            // fewer decoded frames held by the GPU decoder (each a 1080p-4K surface)
            o("hwdec-extra-frames", "2")
            // the frames are converted and scaled on the CPU (render API "sw"): no random dither (it reads as grain on
            // screen), a sharp scaler, every core
            o("zimg-dither", "no"); o("dither-depth", "no")
            o("zimg-scaler", "spline36"); o("zimg-scaler-chroma", "spline36"); o("zimg-threads", "auto")
            o("sub-auto", "no"); o("sub-font-size", "42")
            // captions as on YouTube: white on a see-through black box behind each line (mpv 0.38+; before that, an outline)
            box = o("sub-border-style", "opaque-box") >= 0
            if (box) o("sub-shadow-offset", "0") else o("sub-border-size", "2.5")
            capOpts { k, v -> o(k, v) }
            o("audio-display", "no"); o("audio-client-name", "Tubeless")
            o("audio-device", audioDevice.ifEmpty { "auto" })
            o("terminal", "no"); o("msg-level", "all=warn")
            if (l.mpv_initialize(p) < 0) { l.mpv_terminate_destroy(p); return null }
            val res = PointerByReference()
            if (l.mpv_render_context_create(res, p, LibMpv.params(LibMpv.RENDER_PARAM_API_TYPE to apiType)) >= 0) {
                render = res.value
                l.mpv_render_context_set_update_callback(res.value, onUpdate, null)
                thread(isDaemon = true, name = "mpv-render") { renderLoop(l) }
            }
            h = p
            thread(isDaemon = true, name = "mpv-events") { events(l, p) }
            thread(isDaemon = true, name = "mpv-state") { poll(l, p) }
            return p
        }
    }

    private fun renderLoop(l: LibMpv) {
        while (!closing) {
            wake.tryAcquire(250, TimeUnit.MILLISECONDS)
            synchronized(renderLock) {
                val ctx = render ?: return
                val fresh = l.mpv_render_context_update(ctx) and LibMpv.RENDER_UPDATE_FRAME != 0L
                if (!fresh && !redraw) return@synchronized
                // nothing on screen (another page is open), or the old frames not freed yet: mpv drops the frame and plays on
                if (views == 0 || unfreed.get() > 2) { if (fresh && views > 0) framesSkipped++; return@synchronized }
                val (w, h) = target() ?: return@synchronized
                redraw = false
                val t = target?.takeIf { it.w == w && it.h == h } ?: Target(w, h).also { target?.bitmap?.close(); target = it }
                swSize.setInt(0, w); swSize.setInt(4, h)
                stride.setLong(0, t.bitmap.rowBytes.toLong())
                val p = LibMpv.params(
                    LibMpv.RENDER_PARAM_SW_SIZE to swSize,
                    LibMpv.RENDER_PARAM_SW_FORMAT to pixelFormat,
                    LibMpv.RENDER_PARAM_SW_STRIDE to stride,
                    LibMpv.RENDER_PARAM_SW_POINTER to t.addr,
                )
                val t0 = System.nanoTime()
                if (l.mpv_render_context_render(ctx, p) >= 0) {
                    drawNanos += System.nanoTime() - t0; framesDrawn++; drawSize = w to h
                    t.bitmap.notifyPixelsChanged()
                    show(Image.makeFromBitmap(t.bitmap))      // a copy: mpv can draw the next one meanwhile
                }
            }
        }
    }

    /**
     * The size mpv draws at: the view's (in screen pixels, so Retina gets its full sharpness), but never more
     * than the video itself (no point scaling up on the CPU: Compose does that last step on the GPU) and at most
     * 3840x2160. mpv keeps the video's shape itself.
     */
    private fun target(): Pair<Int, Int>? {
        val v = view
        if (v.width < 2 || v.height < 2) return null
        val vw = videoW.takeIf { it > 0 } ?: 3840
        val vh = videoH.takeIf { it > 0 } ?: 2160
        // the video's size in the view's shape: the frame fits the view, so the larger of the two ratios
        val fit = maxOf(vw.toDouble() / v.width, vh.toDouble() / v.height)
        val s = min(fit, min(3840.0 / v.width, 2160.0 / v.height)).coerceAtMost(1.0)
        return (v.width * s).roundToInt().coerceAtLeast(2) to (v.height * s).roundToInt().coerceAtLeast(2)
    }

    /** puts [img] on screen; the old frame is freed on the UI thread, after any repaint still using it */
    private fun show(img: Image?) {
        val old = front
        front = img
        frames.intValue++
        if (img != null && firstFrameAt == 0L && loadAt > 0) firstFrameAt = System.currentTimeMillis()
        if (old != null) {
            unfreed.incrementAndGet()
            SwingUtilities.invokeLater { old.close(); unfreed.decrementAndGet() }
        }
    }

    private fun clearPicture() = show(null)

    /**
     * Ambient mode (as Android and iOS): the picture on screen now, 32×18 and blurred. Read on the UI
     * thread, where the frames are freed, so the one being copied can't go away meanwhile.
     */
    override suspend fun frame(): androidx.compose.ui.graphics.ImageBitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val img = front ?: return@withContext null
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(32, 18)
        val paint = org.jetbrains.skia.Paint().apply { imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(1.5f, 1.5f, org.jetbrains.skia.FilterTileMode.CLAMP) }
        surface.canvas.drawImageRect(img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(32f, 18f), sampling, paint, true)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }

    // ------------------------------------------------------------------ mpv's side
    /** the frame on screen as PNG, as mpv drew it before the last stretch to the view (PlayProbe) */
    suspend fun snapshot(): ByteArray? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        front?.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
    }

    /** an mpv property as text (PlayProbe) */
    fun prop(name: String): String? { val p = h ?: return null; val l = lib ?: return null; return str(l, p, name) }

    /** an mpv command (as `mpv_command`), for the app's other desktop parts (MpvFx, PipWindow) */
    fun command(vararg a: String) = cmd(*a)

    private fun cmd(vararg a: String) {
        val p = mpv() ?: return
        lib!!.mpv_command(p, arrayOf(*a, null))
    }

    /** Settings → caption size, background and position (#54): (size %, box opacity %, 0 bottom / 1 higher / 2 top) */
    @Volatile private var capStyle = Triple(100, 75, 0)
    /** this mpv draws a box behind captions (0.38+) */
    @Volatile private var box = true

    private fun capOpts(o: (String, String) -> Unit) {
        val (size, bg, pos) = capStyle
        o("sub-scale", (size / 100.0).toString())
        if (box) {
            if (bg > 0) { o("sub-border-style", "opaque-box"); o("sub-border-color", "#%02X080808".format(bg * 255 / 100)); o("sub-border-size", "6") }
            else { o("sub-border-style", "outline-and-shadow"); o("sub-border-color", "#FF000000"); o("sub-border-size", "2.5") }
        }
        o("sub-align-y", if (pos == 2) "top" else "bottom")
        o("sub-margin-y", if (pos == 1) "160" else "40")
    }

    override fun setCaptionStyle(size: Int, bg: Int, pos: Int) {
        capStyle = Triple(size, bg, pos)
        if (h != null) capOpts { k, v -> set(k, v) }
    }

    /** Settings → Read further ahead: 20 minutes and up to 2 GB instead of 5 minutes and 150 MB */
    override fun setReadAhead(long: Boolean) {
        longAhead = long
        set("cache-secs", if (long) "1200" else "300"); set("demuxer-readahead-secs", if (long) "1200" else "300")
        memory()
    }

    @Volatile private var longAhead = false
    @Volatile private var bufferMb = 0
    override val memoryLimit get() = true

    /** Settings → Memory for the video (#87): an exact number of MB, three quarters ahead and a quarter behind */
    override fun setBufferMb(mb: Int) {
        bufferMb = mb
        memory()
    }

    @Volatile private var localSource = false

    private fun memory() {
        if (bufferMb > 0) {
            set("demuxer-max-bytes", "${bufferMb * 3 / 4}MiB"); set("demuxer-max-back-bytes", "${maxOf(bufferMb / 4, 1)}MiB")
        } else {
            set("demuxer-max-bytes", if (longAhead) "2GiB" else if (localSource) "24MiB" else "64MiB")
            set("demuxer-max-back-bytes", if (longAhead) "50MiB" else if (localSource) "8MiB" else "24MiB")
        }
    }

    /** Settings → Sound output (#46): for example ALSA when PipeWire's default doesn't play */
    @Volatile private var audioDevice = ""

    override fun audioDevices(): List<Pair<String, String>> {
        val p = mpv() ?: return emptyList()
        val text = str(lib!!, p, "audio-device-list") ?: return emptyList()
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val name = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                name to (o["description"]?.jsonPrimitive?.content ?: "")
            }
        }.getOrDefault(emptyList())
    }

    override fun setAudioDevice(name: String) {
        if (name == audioDevice) return
        audioDevice = name
        val p = h ?: return
        set("audio-device", name.ifEmpty { "auto" })
        // mpv dropped the sound when the last device wouldn't open: take it up again on this one
        val l = lib!!
        if (stream != null && str(l, p, "current-tracks/audio/id") == null) {
            val n = num(l, p, "track-list/count")?.toInt() ?: 0
            (0 until n).firstOrNull { str(l, p, "track-list/$it/type") == "audio" }
                ?.let { str(l, p, "track-list/$it/id") }?.let { set("aid", it) }
        }
    }

    private fun set(k: String, v: String) {
        val p = mpv() ?: return
        lib!!.mpv_set_property_string(p, k, v)
    }

    private fun events(l: LibMpv, p: Pointer) {
        while (true) {
            val e = l.mpv_wait_event(p, 1.0)
            when (e.getInt(0)) {
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_END_FILE -> {
                    val data = e.getPointer(16)
                    val reason = data?.getInt(0) ?: -1
                    if (reason == LibMpv.END_FILE_REASON_EOF && st.value.videoId != null) st.update { it.copy(ended = true, playing = false) }
                    if (reason == LibMpv.END_FILE_REASON_ERROR) st.update { it.copy(error = "Playback failed", buffering = false) }
                }
                LibMpv.EVENT_FILE_LOADED -> {
                    st.update { it.copy(ended = false, error = null) }
                    // mpv downloads the track here, on this thread, never on the UI's
                    loadedCaption?.let { c -> addSubs(c, loadedTranslate) }
                }
            }
        }
    }

    private fun num(l: LibMpv, p: Pointer, name: String): Double? {
        val r = DoubleByReference()
        return if (l.mpv_get_property(p, name, LibMpv.FORMAT_DOUBLE, r) >= 0) r.value else null
    }

    private fun str(l: LibMpv, p: Pointer, name: String): String? {
        val r = l.mpv_get_property_string(p, name) ?: return null
        return try { r.getString(0, "UTF-8") } finally { l.mpv_free(r) }
    }

    private fun poll(l: LibMpv, p: Pointer) {
        while (!closing) {
            Thread.sleep(200)
            val s = stream ?: continue
            val pos = num(l, p, "time-pos")
            val dur = num(l, p, "duration")
            val paused = str(l, p, "pause") == "yes"
            val idle = str(l, p, "idle-active") == "yes"
            val waiting = str(l, p, "paused-for-cache") == "yes" || str(l, p, "seeking") == "yes"
            val speed = num(l, p, "speed")
            val cached = num(l, p, "demuxer-cache-time")
            // the decoded picture's size (its display shape), so frames are drawn at the video's own sharpness
            val dw = num(l, p, "dwidth")?.toInt() ?: 0
            val dh = num(l, p, "dheight")?.toInt() ?: 0
            if (dw > 0 && dh > 0 && (dw != videoW || dh != videoH)) { videoW = dw; videoH = dh; redraw = true; wake.release() }
            val sound = soundProblem(l, p, playing = pos != null && !paused && !idle)
            keepingUp(st.value)
            st.update {
                it.copy(
                    videoId = s.id,
                    playing = !paused && !idle && !it.ended,
                    // from loadfile until the first frame there is no position yet
                    buffering = !it.ended && it.error == null && (waiting || (!paused && pos == null)),
                    positionMs = pos?.let { x -> (x * 1000).toLong() } ?: it.positionMs,
                    durationMs = dur?.let { x -> (x * 1000).toLong() } ?: (s.lengthSec * 1000L),
                    speed = speed?.toFloat() ?: it.speed,
                    bufferedMs = cached?.let { x -> (x * 1000).toLong() } ?: 0,
                    soundIssue = sound,
                    nowHeight = if (dh > 0) (openedVideo?.height?.takeIf { v -> v > 0 && kotlin.math.abs(v - dh) < 16 } ?: dh) else 0,
                    nowFps = openedVideo?.fps ?: 0,
                    nowItag = openedVideo?.itag ?: 0,
                    nowCodec = openedVideo?.codecFamily ?: "",
                    nowKbps = (num(l, p, "video-bitrate")?.div(1000))?.toInt() ?: 0,
                    dropped = (num(l, p, "frame-drop-count")?.toInt() ?: 0) + (num(l, p, "decoder-frame-drop-count")?.toInt() ?: 0) + (framesSkipped - skippedAtLoad).toInt(),
                )
            }
        }
    }

    /**
     * mpv plays the picture on in silence when the sound can't play, and says so only in its log:
     * find out why, so the app can say it. Checked while playing, and shown once it lasts a second.
     */
    private fun soundProblem(l: LibMpv, p: Pointer, playing: Boolean): String? {
        if (!playing) return st.value.soundIssue
        val track = str(l, p, "current-tracks/audio/id")
        // a track that's there but not playing: mpv drops it when the sound device won't open
        val listed = (0 until (num(l, p, "track-list/count")?.toInt() ?: 0)).any { str(l, p, "track-list/$it/type") == "audio" }
        val problem = when {
            track == null && listed -> "No sound: the speaker or headphones wouldn't open. Pick one in Settings → Playback → Sound output."
            // the audio stream (a separate download) failed: add it once more
            track == null && audioUrl != null -> {
                if (!audioRetried && soundBad >= 5) { audioRetried = true; soundBad = 0; cmd("audio-add", audioUrl!!, "select"); return null }
                "The sound didn't load. Try playing the video again."
            }
            track == null -> null
            str(l, p, "current-ao").isNullOrEmpty() -> "No sound: the speaker or headphones wouldn't open. Pick one in Settings → Playback → Sound output."
            str(l, p, "ao-mute") == "yes" || num(l, p, "ao-volume")?.let { it <= 0.0 } == true -> "No sound: Tubeless is muted or at 0 in the system's volume mixer."
            else -> null
        }
        if (problem == null) { soundBad = 0; return null }
        soundBad++
        return if (soundBad >= 5) problem else st.value.soundIssue
    }

    // ------------------------------------------------------------------ choosing streams
    /**
     * Auto (QUALITY): the height that fills the largest screen without stretching it ([Quality.fit]: 2160p on a
     * Retina MacBook, 1080p on a 1080p monitor), a height picked in Settings or the menu as it is; at that height
     * the fullest stream this computer decodes in hardware ([Quality.best]).
     */
    private fun pickVideo(s: Stream, cap: Int): Format? {
        val (sw, sh) = screenPixels()
        var h = Quality.height(s.video, cap, sw, sh)
        // Auto came down here because this computer dropped frames at a higher one (see [keepingUp])
        if (cap == Quality.AUTO && slowCap > 0) h = minOf(h, slowCap)
        // the picture is drawn as 8-bit SDR on this path: HDR streams would look washed out, so the SDR ones
        return Quality.best(s.video, h, opts.codec, hardwareCodecs, s.lengthSec, sdrOnly = true)
    }

    /** the highest height Auto plays after frames were dropped (0: none); kept until the app restarts */
    @Volatile private var slowCap = 0
    /** when the window started, the frames dropped and drawn by then */
    private var dropMark: Triple<Long, Int, Long>? = null

    /**
     * Auto steps down one height when more than 5 % of the frames were dropped over 10 s of playing (a slower computer
     * than the screen asks for: drawing is on the CPU here), as YouTube's player does. Not for a height picked by hand.
     */
    private fun keepingUp(st: PlayerState) {
        val v = openedVideo
        // only while the picture is on screen: mpv drops every frame nobody draws (another page, minimized, the
        // background), which says nothing about this computer's speed
        if (!st.playing || st.buffering || pinned > 0 || opts.maxHeight != Quality.AUTO || v == null || st.dropped < 0 || st.positionMs < 3000 || views == 0) { dropMark = null; return }
        val now = System.currentTimeMillis()
        val mark = dropMark ?: run { dropMark = Triple(now, st.dropped, framesDrawn); return }
        if (now - mark.first < 10_000) return
        val frames = (now - mark.first) / 1000.0 * (v.fps.takeIf { it > 0 } ?: 30) * st.speed
        val lost = st.dropped - mark.second
        val drawn = framesDrawn - mark.third
        dropMark = Triple(now, st.dropped, framesDrawn)
        // most frames still drawn: a slow computer drops some and draws the rest; none drawn means not on screen
        if (drawn < frames * 0.5 || lost <= frames * 0.05) return
        val lower = stream?.video?.map { it.height }?.filter { it < v.height }?.maxOrNull() ?: return
        System.err.println("mpv: $lost of ${frames.toInt()} frames dropped at ${v.height}p, Auto steps down to ${lower}p")
        slowCap = lower
        dropMark = null
        open(st.positionMs)
    }

    /** the largest screen's size in pixels (Retina counted); 0 when there is no screen */
    private fun screenPixels(): Pair<Int, Int> = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { d ->
            val c = d.defaultConfiguration
            (c.bounds.width * c.defaultTransform.scaleX).roundToInt() to (c.bounds.height * c.defaultTransform.scaleY).roundToInt()
        }.maxByOrNull { it.first * it.second } ?: (0 to 0)
    }.getOrDefault(0 to 0)

    /**
     * The codec families decoded by the GPU here: on a Mac, what VideoToolbox says (AV1 from M3 on, VP9 on Apple
     * silicon); elsewhere H.264 and VP9 (nearly every GPU since 2016), as before.
     */
    private val hardwareCodecs: Set<String> by lazy {
        if (!System.getProperty("os.name").startsWith("Mac")) return@lazy setOf("h264", "vp9")
        val vt = runCatching { com.sun.jna.Native.load("VideoToolbox", VideoToolbox::class.java) }.getOrNull() ?: return@lazy setOf("h264", "vp9")
        fun code(fourcc: String) = fourcc.fold(0) { a, ch -> (a shl 8) or ch.code }
        // VP9 is a supplemental decoder on macOS 11+: it answers only once registered (as FFmpeg does before using it)
        runCatching { vt.VTRegisterSupplementalVideoDecoderIfAvailable(code("vp09")) }
        fun hw(fourcc: String) = runCatching { vt.VTIsHardwareDecodeSupported(code(fourcc)).toInt() and 0xff != 0 }.getOrDefault(false)
        setOfNotNull("h264", "vp9".takeIf { hw("vp09") }, "av1".takeIf { hw("av01") })
    }

    @Suppress("FunctionName")
    private interface VideoToolbox : com.sun.jna.Library {
        fun VTIsHardwareDecodeSupported(codecType: Int): Byte
        fun VTRegisterSupplementalVideoDecoderIfAvailable(codecType: Int)
    }

    /** for the logs: what this computer decodes in hardware */
    val hardware: Set<String> get() = hardwareCodecs

    /** the language you picked for this video, else the original track */
    private fun pickAudio(s: Stream): Format? = app.tubeless.engine.AudioPick.best(s.audio, audioLang)

    /** what was opened last (for PlayProbe and the logs): the picture's format and the sound's */
    @Volatile var openedVideo: Format? = null
        private set
    @Volatile var openedAudio: Format? = null
        private set
    /** when load() was called for the current video, and when its first frame came (0: not yet) */
    @Volatile var loadAt = 0L
        private set
    @Volatile var firstFrameAt = 0L

    private fun source(u: String) = if (u.startsWith("file:")) java.io.File(java.net.URI(u)).absolutePath else if (u.startsWith("http://127.0.0.1:")) u else LocalProxy.wrap(u)

    private fun open(startMs: Long) {
        val s = stream ?: return
        if (mpv() == null) {
            st.update { it.copy(error = LibMpv.MISSING, buffering = false) }
            return
        }
        val heights = s.video.map { it.height }.distinct().sorted()
        val languages = s.audio.mapNotNull { f -> f.audioLang?.let { it to (f.audioTrackName ?: it) } }.distinctBy { it.first }
        val audioNow = if (s.live || s.video.isEmpty()) null else pickAudio(s)?.audioLang
        st.update { it.copy(heights = heights, height = pinned, buffering = true, audioTracks = languages, audioTrack = audioNow) }
        val url: String
        cmd("change-list", "audio-files", "clr", "")
        audioUrl = null
        audioRetried = false
        soundBad = 0
        if (s.live || s.video.isEmpty()) {
            url = s.hls ?: return
        } else if (opts.audioOnly && s.audio.isNotEmpty()) {
            // music: the sound alone (the app shows the cover)
            url = source((pickAudio(s) ?: return).url)
        } else {
            val cap = if (pinned > 0) pinned else opts.maxHeight
            val v = pickVideo(s, cap) ?: return
            openedVideo = v
            // through the local proxy, which fetches in 10 MB pieces (YouTube crawls on one long request);
            // a downloaded video is opened straight from its files
            url = source(v.url)
            pickAudio(s)?.let { a -> openedAudio = a; audioUrl = source(a.url); cmd("change-list", "audio-files", "append", audioUrl!!) }
        }
        // a SABR video is already a file on this disk (Sabr's sparse cache): a small read-ahead in memory is enough
        localSource = url.startsWith("http://127.0.0.1:") && "/sabr?" in url
        memory()
        set("start", if (startMs > 0) "+%.1f".format(java.util.Locale.ROOT, startMs / 1000.0) else "none")
        set("loop-file", if (opts.loop) "inf" else "no")
        set("speed", opts.speed.toString())
        set("pause", if (opts.autoplay) "no" else "yes")
        set("force-media-title", opts.title.ifEmpty { s.title })
        // a live stream from the connected server is opened by mpv itself and needs the server's login
        set("http-header-fields", ServerVideo.authFor(url)?.let { "Authorization: $it" } ?: "")
        // live: two segments behind the edge (FFmpeg starts three back)
        cmd("set", "demuxer-lavf-o", if (s.live) "live_start_index=-2" else "")
        cmd("loadfile", url, "replace")
    }

    // ------------------------------------------------------------------ VideoPlayer
    override fun load(s: Stream, o: PlayOptions) {
        loadAt = System.currentTimeMillis(); firstFrameAt = 0; openedVideo = null; openedAudio = null; skippedAtLoad = framesSkipped
        // the language Playback chose (the original, or a dub in yours: AudioPick.preferred); the same video again keeps its own
        audioLang = o.audioLang ?: audioLang.takeIf { stream?.id == s.id }
        stream = s
        opts = o
        pinned = 0
        caption = o.captions
        translateTo = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        loadedCaption = caption ?: o.standbyCaption
        loadedTranslate = if (caption != null) translateTo else o.standbyTranslate
        set("sub-visibility", if (caption != null) "yes" else "no")
        val muted = st.value.muted
        st.value = PlayerState(videoId = s.id, buffering = true, speed = o.speed, captions = caption, captionsTranslated = translateTo, volume = o.volume, muted = muted)
        clearPicture()
        set("volume", (o.volume * 100).roundToInt().toString())
        open(o.startMs)
    }

    override fun play() {
        if (st.value.ended) { st.update { it.copy(ended = false) }; open(0); return }
        set("pause", "no")
    }

    override fun pause() = set("pause", "yes")
    override fun seekTo(ms: Long) {
        if (st.value.ended) { st.update { it.copy(ended = false) }; open(ms); return }
        st.update { it.copy(positionMs = ms) }
        cmd("seek", "%.1f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute")
    }
    override fun setSpeed(x: Float) { opts = opts.copy(speed = x); set("speed", x.toString()); st.update { it.copy(speed = x) } }

    override fun setHeight(h: Int) {
        pinned = h
        open(st.value.positionMs)
    }

    override fun setAudioTrack(lang: String) {
        audioLang = lang
        open(st.value.positionMs)
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        caption = c
        this.translateTo = translateTo
        st.update { it.copy(captions = c, captionsTranslated = translateTo.takeIf { c != null }) }
        if (c == null || (c == loadedCaption && translateTo == loadedTranslate)) {
            set("sub-visibility", if (c != null) "yes" else "no")
            return
        }
        loadedCaption = c
        loadedTranslate = translateTo
        // another track: mpv downloads it, so not on the UI thread
        thread(isDaemon = true, name = "mpv-subs") {
            cmd("sub-remove")
            addSubs(c, translateTo)
            set("sub-visibility", "yes")
        }
    }

    /** a caption track into mpv; YouTube often turns down its machine translations (HTTP 429), then the track as it is */
    private fun addSubs(c: Caption, to: String?) {
        val u = YouTube.captionUrl(c, to)
        val f = Net.captionFile(u)
        if (f == null && to != null) {
            loadedTranslate = null
            if (caption == c) { translateTo = null; st.update { it.copy(captionsTranslated = null) } }
            return addSubs(c, null)
        }
        cmd("sub-add", f ?: u, "select", c.name)
    }

    override fun setLoop(on: Boolean) { opts = opts.copy(loop = on); set("loop-file", if (on) "inf" else "no") }

    override val hasVolume get() = true
    override fun setVolume(v: Float) {
        val x = v.coerceIn(0f, 1f)
        set("volume", (x * 100).roundToInt().toString())
        st.update { it.copy(volume = x) }
    }

    override fun setMuted(on: Boolean) {
        set("mute", if (on) "yes" else "no")
        st.update { it.copy(muted = on) }
    }

    override fun stop() {
        stream = null
        cmd("stop")
        clearPicture()
        st.value = PlayerState(volume = st.value.volume, muted = st.value.muted)
    }

    @Composable
    override fun View(modifier: Modifier) {
        if (!available) {
            Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
                Text(LibMpv.MISSING, color = Color.White)
            }
            return
        }
        DisposableEffect(Unit) {
            views++
            redraw = true
            onDispose { views-- }
        }
        // transparent until the first frame, so a thumbnail underneath shows through
        Canvas(modifier.onSizeChanged { view = it; redraw = true; wake.release() }) {
            frames.intValue          // repaint on every new frame
            val img = front ?: return@Canvas
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(
                    img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(size.width, size.height), sampling, null, true,
                )
            }
        }
    }

    fun shutdown() {
        closing = true
        val p = synchronized(lock) { h.also { h = null } } ?: return
        synchronized(renderLock) {
            render?.let { lib?.mpv_render_context_free(it) }   // before the core goes away
            render = null
        }
        lib?.mpv_terminate_destroy(p)
    }
}
