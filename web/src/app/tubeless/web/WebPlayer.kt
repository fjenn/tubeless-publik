package app.tubeless.web

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import app.tubeless.engine.Caption
import app.tubeless.engine.Dash
import app.tubeless.engine.Net
import app.tubeless.engine.Quality
import app.tubeless.engine.Stream
import app.tubeless.engine.YouTube
import app.tubeless.ui.PlayOptions
import app.tubeless.ui.PlayerState
import app.tubeless.ui.VideoPlayer
import app.tubeless.web.jsLog
import app.tubeless.web.toByteArray
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.khronos.webgl.ArrayBuffer
import kotlin.js.Promise

/**
 * The browser's player: Shaka Player (extension/vendor) on the DASH manifest Dash.kt writes, the same
 * one Android's ExoPlayer gets; live videos and HLS-only answers go to Shaka as HLS. The <video> lies
 * under the Compose canvas, which [View] leaves see-through, so the Compose controls draw on top.
 */
class WebPlayer : VideoPlayer {
    private val s = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = s
    private val scope = MainScope()
    private var current: Stream? = null

    /** the codec families this browser decodes, best first (Dash.manifest picks the first one the video has) */
    private val codecs: List<String> by lazy {
        listOfNotNull(
            "av1".takeIf { jsSupports("video/mp4; codecs=\"av01.0.08M.08\"") && jsSupports("video/mp4; codecs=\"av01.0.12M.08\"") },
            "vp9".takeIf { jsSupports("video/webm; codecs=\"vp9\"") },
            "h264",
        )
    }

    /** the families decoded in hardware (MediaCapabilities' powerEfficient); everything this browser plays until it answered */
    private fun hardware(): Set<String> {
        val hw = jsHw() ?: return codecs.toSet()
        return codecs.filter { c -> hw.contains("\"$c\":true") }.toSet().ifEmpty { codecs.toSet() }
    }

    private var lastNow = 0.0

    init {
        jsOnTime { pos, dur, playing, buffering, ended, height ->
            s.update { it.copy(positionMs = (pos * 1000).toLong(), durationMs = (dur * 1000).toLong(), playing = playing, buffering = buffering, ended = ended) }
            // what is on screen (QUALITY): read twice a second at most
            val t = jsDate()
            if (t - lastNow > 500) { lastNow = t; readNow() }
        }
    }

    private fun readNow() {
        val o = runCatching { Json.parseToJsonElement(jsNow()).jsonObject }.getOrNull() ?: return
        fun n(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 0
        val codec = o["codec"]?.jsonPrimitive?.contentOrNull.orEmpty()
        s.update {
            it.copy(nowHeight = n("h"), nowFps = n("fps"), nowItag = n("itag"), nowKbps = n("kbps"),
                nowCodec = if (codec.isEmpty()) "" else app.tubeless.engine.Format(0, "", "", codec).codecFamily,
                dropped = o["dropped"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: -1)
        }
    }

    /**
     * Auto (QUALITY): the height that fills this screen ([Quality.fit], screen × devicePixelRatio), and the codec family
     * whose stream at that height is the fullest one this browser decodes in hardware ([Quality.best]); Shaka adapts
     * below that height only.
     */
    private fun plan(st: Stream, cap: Int): Pair<List<String>, Int> {
        val usable = st.video.filter { it.indexRange != null && it.codecFamily in codecs }
        val h = Quality.height(usable, cap, jsScreenW(), jsScreenH())
        val fam = Quality.best(usable, h, "", hardware(), st.lengthSec)?.codecFamily
        val order = listOfNotNull(fam) + codecs.filter { it != fam }
        return order to h
    }

    override fun load(st: Stream, o: PlayOptions) {
        current = st
        s.value = PlayerState(videoId = st.id, buffering = true, speed = o.speed, volume = s.value.volume, muted = s.value.muted)
        val hls = st.hls != null && (st.live || st.video.none { it.indexRange != null })
        // a codec picked in Settings or the menu goes first; else the one Auto finds best at the screen's height
        val (auto, fit) = plan(st, o.maxHeight)
        val order = o.codec.takeIf { it in codecs }?.let { c -> listOf(c) + codecs.filter { it != c } } ?: auto
        val cap = if (fit == Quality.AUTO) 0 else fit
        // every height goes in (the menu can pick any); Auto's cap is the player's limit, not the manifest's
        val manifest = if (hls) st.hls!! else Dash.manifest(st, order, if (o.maxHeight != Quality.AUTO) o.maxHeight else Int.MAX_VALUE)
        jsLog("load ${st.id} via ${st.client}: ${if (hls) "HLS" else "DASH ${order.firstOrNull { c -> st.video.any { it.codecFamily == c } }} up to ${cap}p (hardware ${hardware()})"}")
        // the website's player (site/instant.js) plays the formats itself: it wants the video, the HLS link and the mode
        jsHint(st.id, st.hls ?: "", st.client, o.audioOnly)
        jsSession(st.title, st.author, "https://i.ytimg.com/vi/${st.id}/hqdefault.jpg") { a -> action(a) }
        scope.launch {
            val err = jsLoad(manifest, hls, o.startMs / 1000.0, o.autoplay, cap).await<JsString>().toString()
            if (current !== st) return@launch
            if (err.isNotEmpty()) { s.update { it.copy(error = err, buffering = false) }; return@launch }
            setSpeed(o.speed)
            setLoop(o.loop)
            // the language Playback chose (the original, or a dub in yours: AudioPick.preferred)
            o.audioLang?.let { jsAudio(it) }
            readTracks()
            if (o.captions != null) setCaptions(o.captions, o.captionLang)
        }
    }

    private fun readTracks() {
        val t = runCatching { Json.parseToJsonElement(jsTracks()).jsonObject }.getOrNull() ?: return
        s.update {
            it.copy(
                heights = t["heights"]!!.jsonArray.map { h -> h.jsonPrimitive.int },
                audioTracks = t["audio"]!!.jsonArray.map { p -> (p as JsonArray).let { a -> a[0].jsonPrimitive.content to a[1].jsonPrimitive.content } },
                audioTrack = t["lang"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun action(a: String) = when (a) {
        "play" -> play(); "pause" -> pause()
        "seekbackward" -> seekTo((s.value.positionMs - 10_000).coerceAtLeast(0)); "seekforward" -> seekTo(s.value.positionMs + 10_000)
        else -> {}
    }

    override fun play() = jsCall("play")
    override fun pause() = jsCall("pause")
    override fun seekTo(ms: Long) = jsSeek(ms / 1000.0)
    override fun setSpeed(x: Float) { jsSpeed(x.toDouble()); s.update { it.copy(speed = x) } }
    override fun setHeight(h: Int) { jsHeight(h); s.update { it.copy(height = h) } }
    override fun setAudioTrack(lang: String) { jsAudio(lang); s.update { it.copy(audioTrack = lang) } }
    override fun setLoop(on: Boolean) = jsLoop(on)
    override fun stop() { current = null; jsCall("stop") }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        s.update { it.copy(captions = c, captionsTranslated = translateTo.takeIf { c != null }) }
        if (c == null) { jsCaptions(null); return }
        scope.launch { runCatching { Net.get(YouTube.captionUrl(c, translateTo)) }.onSuccess { jsCaptions(it) }.onFailure { jsLog("captions: $it") } }
    }

    override val hasVolume get() = true
    override fun setVolume(v: Float) { jsVolume(v.toDouble()); s.update { it.copy(volume = v) } }
    override fun setMuted(on: Boolean) { jsMuted(on); s.update { it.copy(muted = on) } }

    /** the ambient glow: a 32×18 copy of the frame */
    override suspend fun frame(): ImageBitmap? {
        val buf = jsFrame(32, 18) ?: return null
        val rgba = buf.toByteArray()
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocPixels(org.jetbrains.skia.ImageInfo(32, 18, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
        bmp.installPixels(rgba)
        return bmp.asComposeImageBitmap()
    }

    fun pip() = jsCall("pip")

    @Composable
    override fun View(modifier: Modifier) {
        val density = LocalDensity.current.density
        // a hole in the canvas: the <video> under it shows through, the controls draw over it
        Box(modifier
            .drawWithContent { drawRect(Color.Transparent, blendMode = BlendMode.Clear); drawContent() }
            .onGloballyPositioned { c ->
                val r = c.boundsInWindow()
                jsPlace(r.left / density.toDouble(), r.top / density.toDouble(), r.width / density.toDouble(), r.height / density.toDouble())
            })
        DisposableEffect(Unit) { onDispose { jsPlace(0.0, 0.0, 0.0, 0.0) } }
    }
}


private fun jsHint(id: String, hls: String, client: String, audioOnly: Boolean): Unit =
    js("{ if (tubeless.player.hint) tubeless.player.hint(id, hls, client, audioOnly) }")
private fun jsSupports(t: String): Boolean = js("tubeless.player.supports(t)")
private fun jsOnTime(f: (Double, Double, Boolean, Boolean, Boolean, Int) -> Unit): Unit = js("tubeless.player.onTime(f)")
private fun jsLoad(m: String, hls: Boolean, start: Double, autoplay: Boolean, maxHeight: Int): Promise<JsString> =
    js("tubeless.player.load(m, hls, start, autoplay, maxHeight)")
private fun jsTracks(): String = js("tubeless.player.tracks()")
private fun jsNow(): String = js("(tubeless.player.now ? tubeless.player.now() : '{}')")
private fun jsHw(): String? = js("(tubeless.player.hw ? JSON.stringify(tubeless.player.hw) : null)")
// the screen, or the window when it reports more (a headless browser says 400×300 for any window)
private fun jsScreenW(): Int = js("Math.round(Math.max(screen.width, window.outerWidth || 0, window.innerWidth) * (window.devicePixelRatio || 1))")
private fun jsScreenH(): Int = js("Math.round(Math.max(screen.height, window.outerHeight || 0, window.innerHeight) * (window.devicePixelRatio || 1))")
private fun jsDate(): Double = js("Date.now()")
private fun jsCall(name: String): Unit = js("tubeless.player[name]()")
private fun jsSeek(t: Double): Unit = js("tubeless.player.seek(t)")
private fun jsSpeed(x: Double): Unit = js("tubeless.player.speed(x)")
private fun jsHeight(h: Int): Unit = js("tubeless.player.setHeight(h)")
private fun jsAudio(l: String): Unit = js("tubeless.player.setAudio(l)")
private fun jsLoop(on: Boolean): Unit = js("tubeless.player.loop(on)")
private fun jsVolume(x: Double): Unit = js("tubeless.player.volume(x)")
private fun jsMuted(on: Boolean): Unit = js("tubeless.player.muted(on)")
private fun jsCaptions(vtt: String?): Unit = js("{ tubeless.player.setCaptions(vtt) }")
private fun jsFrame(w: Int, h: Int): ArrayBuffer? = js("tubeless.player.frame(w, h)")
private fun jsPlace(x: Double, y: Double, w: Double, h: Double): Unit = js("tubeless.player.place(x, y, w, h)")
private fun jsSession(title: String, artist: String, art: String, f: (String) -> Unit): Unit = js("tubeless.player.session(title, artist, art, f)")
