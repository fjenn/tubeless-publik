package app.tubeless.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import app.tubeless.engine.Caption
import app.tubeless.engine.Net
import app.tubeless.engine.ServerVideo
import app.tubeless.engine.Stream
import app.tubeless.engine.YouTube
import app.tubeless.ui.PlayOptions
import app.tubeless.ui.PlayerState
import app.tubeless.ui.VideoPlayer
import kotlinx.cinterop.CValue
import platform.AVFoundation.addOutput
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.extendedLanguageTag
import platform.AVFoundation.languageCode
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import platform.AVFoundation.muted
import platform.AVFoundation.volume
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVKit.AVPictureInPictureController
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.appliesMediaSelectionCriteriaAutomatically
import platform.AVFoundation.assetCache
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.AVFoundation.asset
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.playImmediatelyAtRate
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.preferredPeakBitRate
import platform.AVFoundation.prerollAtRate
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.configuredTimeOffsetFromLive
import platform.AVFoundation.automaticallyPreservesTimeOffsetFromLive
import platform.AVFoundation.preferredMaximumResolution
import platform.AVFoundation.presentationSize
import platform.AVFoundation.accessLog
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGRectEqualToRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * The iPad's player: AVPlayer on YouTube's HLS stream (one address, every quality from 144p to
 * 4K, sound included). Captions are drawn here in Compose from the cleaned WebVTT, because
 * AVPlayer can't take a side-loaded track for HLS.
 */
class IosPlayer : VideoPlayer {
    private companion object {
        /** the players waiting with a Short (see [Warm]) */
        const val MAX_WARM = 3
        /** a waiting Short's limits: about 720p of an upright Short, a few seconds */
        const val WARM_PEAK = 2_000_000.0
        const val WARM_AHEAD = 4.0
    }
    private val st = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> get() = st

    /** the player on screen; Shorts swap in a neighbour's already-buffered one (see [warm]) */
    var av = AVPlayer()
        private set
    private val scope = MainScope()
    private var stream: Stream? = null
    private var opts = PlayOptions()
    /** where to start once the item is ready (AVPlayer ignores a seek before that); -1 = done */
    private var startAt = -1L
    /** the player's picture-in-picture button (nothing happens while AVPlayer can't yet) */
    fun startPip() {
        val pip = picture.pip
        platform.Foundation.NSLog("PiP: start (controller ${pip != null}, possible ${pip?.pictureInPicturePossible}, on screen ${picture.window != null})")
        pip?.startPictureInPicture()
    }

    /** the sound is chosen once per video, as soon as AVFoundation has read the list (before it plays a piece of the wrong one) */
    private var audioPicked = false
    /** the language to play ([PlayOptions.audioLang], then a pick from the sheet); null: the original */
    private var audioWanted: String? = null
    private var heightsJob: Job? = null
    private var capsJob: Job? = null
    private var cues by mutableStateOf<List<Cue>>(emptyList())
    /** Settings → caption size (%), background (% opacity) and position (0 bottom, 1 higher, 2 top) (#54) */
    private var capStyle by mutableStateOf(Triple(100, 75, 0))

    override fun setCaptionStyle(size: Int, bg: Int, pos: Int) { capStyle = Triple(size, bg, pos) }
    private var readAhead = false

    /**
     * Stable volume on the iPhone: HLS gives AVPlayer no way into the sound itself (an audio mix or
     * processing tap is ignored for HTTP Live Streaming), so loud videos are brought down to YouTube's
     * reference level from the loudness YouTube measured for them (playerConfig.audioConfig.loudnessDb),
     * as YouTube's own volume normalisation does; quieter ones play as they are. No voice boost here.
     */
    private var stable = false
    private val fx = object : app.tubeless.ui.AudioFx {
        override val canVoice get() = false
        override fun apply(stable: Boolean, voice: Boolean) { this@IosPlayer.stable = stable; gain() }
    }
    init { app.tubeless.ui.Perks.fx = fx }

    private fun gain() {
        val db = stream?.loudnessDb?.takeIf { stable && it > 0.0 } ?: 0.0
        av.volume = kotlin.math.exp(-db.coerceAtMost(20.0) / 20.0 * kotlin.math.ln(10.0)).toFloat()
    }

    /**
     * The one picture of the player (one AVPlayerLayer, one picture-in-picture controller), moved into
     * whichever place shows the video now: the video page, full screen, Shorts. A new layer and PiP
     * controller each time the video changed place piled up on the same AVPlayer and fought over it
     * (a black or misplaced picture, PiP that stopped working) after going back and forth a few times.
     */
    private val picture by lazy { PlayerView(av) }
    /** the places on screen, newest last: the newest one gets the picture */
    private val hosts = ArrayList<PlayerHost>()

    private fun place() {
        val h = hosts.lastOrNull() ?: return
        if (picture.superview !== h) h.addSubview(picture)
        if (!CGRectEqualToRect(picture.frame, h.bounds)) picture.setFrame(h.bounds)
    }

    private fun hostMoved(h: PlayerHost, onScreen: Boolean) {
        hosts.remove(h)
        if (onScreen) hosts.add(h)
        place()
    }

    init {
        // the E2E event log (scripts/e2e-m3.sh): TUBELESS_E2E_LOG in the launch environment
        app.tubeless.ui.E2E.path = platform.Foundation.NSProcessInfo.processInfo.environment["TUBELESS_E2E_LOG"] as? String
        // sound even with the silent switch on, and the audio keeps playing with the screen locked
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)
        // AVPlayer would pick the audio track in the device's language (an auto-dub in Dutch, say) over the original
        av.appliesMediaSelectionCriteriaAutomatically = false
        scope.launch {
            while (true) {
                tick()
                delay(250)
            }
        }
    }

    private fun secs(t: CValue<CMTime>): Double = CMTimeGetSeconds(t).takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private fun tick() {
        val item = av.currentItem ?: return
        if (startAt >= 0 && item.status == AVPlayerItemStatusReadyToPlay) {
            if (startAt > 0) av.seekToTime(CMTimeMake(startAt, 1000))
            startAt = -1
        }
        if (!audioPicked && item.status == AVPlayerItemStatusReadyToPlay) {
            audioPicked = true
            pickAudio(item)
        }
        val dur = secs(item.duration)
        val pos = secs(av.currentTime())
        val playing = av.timeControlStatus == AVPlayerTimeControlStatusPlaying
        val waiting = av.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        val ended = dur > 0 && pos >= dur - 0.4 && !playing
        if (ended && opts.loop) {
            av.seekToTime(CMTimeMake(0, 1000))
            av.playImmediatelyAtRate(opts.speed)
        }
        val failed = item.status == AVPlayerItemStatusFailed
        st.update {
            it.copy(
                playing = playing,
                buffering = waiting || (startAt >= 0 && !failed),
                positionMs = if (startAt > 0) startAt else (pos * 1000).toLong(),
                durationMs = (dur * 1000).toLong().takeIf { d -> d > 0 } ?: it.durationMs,
                ended = ended && !opts.loop,
                error = if (failed) item.error?.localizedDescription ?: "Playback failed" else null,
            )
        }
        now(item)
    }

    private var startJob: Job? = null

    /**
     * Until the picture moves, the state is read every frame instead of every 250 ms: the spinner goes and the
     * position starts the moment AVPlayer plays (a quarter second sooner on every swipe through Shorts).
     */
    private fun firstFrame(unlimit: AVPlayerItem? = null) {
        startJob?.cancel()
        startJob = scope.launch {
            for (i in 0 until 600) {
                delay(16)
                tick()
                if (st.value.playing && st.value.positionMs > 0) break
            }
            // a Short that waited small (see Warm): its limits go a moment after it moves, so AVPlayer climbs to what Auto
            // allows; lifted at the swipe, AVPlayer changed variant before the first frame and the swipe waited ~1 s
            if (unlimit != null) {
                delay(1500)
                if (av.currentItem === unlimit) {
                    unlimit.preferredPeakBitRate = 0.0
                    unlimit.preferredForwardBufferDuration = if (readAhead) 1200.0 else 0.0
                }
            }
        }
    }

    private var nowTicks = 0
    /** this video's HLS variants: itag to (codec, frame rate) */
    private var variants: Map<Int, Pair<String, Int>> = emptyMap()

    /**
     * What is on screen (QUALITY), from the item's access log: the variant AVPlayer picked (its list's address names
     * YouTube's itag), its bitrate and the frames dropped; the picture's height from presentationSize. Every second.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun now(item: AVPlayerItem) {
        if (nowTicks++ % 4 != 0) return
        val h = item.presentationSize.useContents { height.toInt() }
        val ev = item.accessLog()?.events?.lastOrNull() as? platform.AVFoundation.AVPlayerItemAccessLogEvent
        val uri = ev?.URI ?: ""
        val itag = Regex("/itag/(\\d+)").find(uri)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val f = variants[itag]?.let { (c, fps) -> app.tubeless.engine.Format(itag, "", "", c, fps = fps) } ?: stream?.video?.firstOrNull { it.itag == itag }
        val dropped = item.accessLog()?.events?.sumOf { (it as? platform.AVFoundation.AVPlayerItemAccessLogEvent)?.numberOfDroppedVideoFrames?.toInt()?.coerceAtLeast(0) ?: 0 } ?: -1
        st.update {
            it.copy(nowHeight = h, nowItag = itag, nowFps = f?.fps ?: 0, nowCodec = f?.codecFamily ?: "",
                nowKbps = ((ev?.indicatedBitrate ?: 0.0) / 1000).toInt(), dropped = dropped)
        }
    }

    /** a video's item, set up as every load wants it, with its ambient-mode output; null without an HLS address */
    private fun makeItem(s: Stream, o: PlayOptions): Pair<AVPlayerItem, platform.AVFoundation.AVPlayerItemVideoOutput>? {
        val hls = s.hls ?: return null
        // from the connected server, the list and every piece need its login
        val headers = ServerVideo.headersFor(hls)
        val item = if (headers == null) AVPlayerItem(uRL = NSURL(string = hls))
            else AVPlayerItem(asset = AVURLAsset(uRL = NSURL(string = hls), options = mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to headers)))
        // a saved video is one local .mp4: AVPlayer reads the file and nothing else (the E2E log shows the address it got)
        if (s.client == "offline") app.tubeless.ui.E2E.log("player_item", "videoId" to s.id, "scheme" to ((item.asset as? AVURLAsset)?.URL?.scheme ?: ""),
            "file" to hls.substringAfterLast('/'))
        // a download plays the quality that was saved: a cap (e.g. the mobile-data one, which applies as soon as Wi-Fi is
        // gone) would make AVPlayer want a variant that isn't in the package and go to the network for it
        if (s.client != "offline") limit(item, auto(o.maxHeight, s))
        item.audioTimePitchAlgorithm = pitchFor(o.speed)
        if (readAhead) item.preferredForwardBufferDuration = 1200.0
        // live: 8 s behind the edge (two segments), kept there
        if (s.live) { item.configuredTimeOffsetFromLive = CMTimeMake(8_000, 1000); item.automaticallyPreservesTimeOffsetFromLive = true }
        // ambient mode: small copies of the picture are read from here (frame())
        val out = platform.AVFoundation.AVPlayerItemVideoOutput(pixelBufferAttributes = null)
        item.addOutput(out)
        return item to out
    }

    /**
     * Shorts' neighbours, each loading in a muted, paused AVPlayer of its own that nobody sees: the next one (preload)
     * and the one just swiped away (keepReady). A swipe makes that player [av] and shows it in the one picture layer, so
     * the first frame comes at once instead of after the list, the first pieces and the decoder's start (about 2.5 s a
     * swipe on the simulator before). The player moves, not its item: AVFoundation refuses an item that has been in
     * another player (an exception, the app gone). [MAX_WARM] at most (the next two and the one before, as YouTube's app):
     * the oldest goes when another comes. A [Warm.standby] one (the first Short, buffered while Home shows) comes on top
     * and waits through [stop], [trimPreloads] and other videos until it is played or another standby replaces it.
     *
     * A waiting item starts small (YouTube does the same): at most [WARM_PEAK] bits/s and [WARM_AHEAD] seconds ahead,
     * so the next two are ready quickly and don't take the connection from the one playing; a moment after it moves on
     * screen both limits go and AVPlayer climbs to the quality Auto allows (see [firstFrame]).
     */
    private class Warm(val stream: Stream, val item: AVPlayerItem, val out: platform.AVFoundation.AVPlayerItemVideoOutput, val player: AVPlayer,
        val standby: Boolean = false)
    private val warm = ArrayList<Warm>()

    private fun keepWarm(w: Warm) {
        if (w.standby) warm.filter { it.standby }.forEach { release(it) }
        warm.add(w)
        while (warm.count { !it.standby } > MAX_WARM) release(warm.first { !it.standby })
    }

    private fun release(w: Warm) {
        warm.remove(w)
        w.player.pause()
        w.player.replaceCurrentItemWithPlayerItem(null)
    }

    private fun idlePlayer(): AVPlayer = AVPlayer().also {
        it.muted = true
        it.appliesMediaSelectionCriteriaAutomatically = false
    }

    /** [p] becomes the player on screen, sounding as [old] did */
    private fun swapTo(p: AVPlayer, old: AVPlayer) {
        p.muted = old.muted
        av = p
        picture.show(p)
    }

    /** the waiting players go, but a [Warm.standby] one unless [all] */
    private fun dropWarm(all: Boolean = false) {
        warm.filter { all || !it.standby }.forEach { release(it) }
    }

    override fun trimPreloads(keep: Collection<String>) {
        warm.filter { !it.standby && it.stream.id !in keep }.forEach { release(it) }
    }

    override fun preload(s: Stream, o: PlayOptions) {
        if (stream?.id == s.id || s.client == "offline") return
        warm.firstOrNull { it.stream.id == s.id }?.let { warm.remove(it); warm.add(it); return }
        val (item, out) = makeItem(s, o) ?: return
        item.preferredPeakBitRate = WARM_PEAK
        item.preferredForwardBufferDuration = WARM_AHEAD
        val p = idlePlayer()
        val w = Warm(s, item, out, p, standby = o.standby)
        keepWarm(w)
        // its sound chosen before it buffers (the list's first track is often a dub: see pickAudio), and only then given
        // to its player: a choice made after the start threw away the sound read so far and read the right one again
        // (Shorts with 8–21 dubs waited 2–4 s on the swipe). load() keeps that choice.
        item.asset.loadValuesAsynchronouslyForKeys(listOf("availableMediaCharacteristicsWithMediaSelectionOptions")) {
            dispatch_async(dispatch_get_main_queue()) {
                item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)?.let { group ->
                    val options = group.options.filterIsInstance<AVMediaSelectionOption>()
                    app.tubeless.ui.audioOption(options, o.audioLang, s.audio)?.let { item.selectMediaOption(it, inMediaSelectionGroup = group) }
                }
                if (w in warm) attach(w)
            }
        }
    }

    /**
     * A waiting Short's item goes into its player: a paused player still reads it (the lists and the first seconds come
     * in), and once it is ready a preroll starts the decoders, so play() shows the first frame without waiting for them.
     */
    private fun attach(w: Warm) {
        if (w.player.currentItem === w.item) return
        w.player.replaceCurrentItemWithPlayerItem(w.item)
        scope.launch {
            repeat(200) {
                delay(50)
                if (w.player.currentItem !== w.item || w.player.rate != 0f || w !in warm) return@launch
                if (w.item.status == AVPlayerItemStatusReadyToPlay) { w.player.prerollAtRate(1f, null); return@launch }
                if (w.item.status == AVPlayerItemStatusFailed) return@launch
            }
        }
    }

    override fun load(s: Stream, o: PlayOptions) {
        val before = stream
        val beforeItem = av.currentItem
        val beforeOut = frames
        val keep = opts.keepReady && o.keepReady
        stream = s
        opts = o
        if (s.hls == null) {
            st.value = PlayerState(videoId = s.id, error = "This video has no stream the iPad can play")
            return
        }
        val ready = warm.firstOrNull { it.stream.id == s.id }
        val old = av
        val park = keep && before != null && beforeItem != null && before.id != s.id && beforeOut != null
        if (ready != null) {
            warm.remove(ready)
            // swiped to before its sound was chosen: it starts now, as it is
            if (ready.player.currentItem !== ready.item) ready.player.replaceCurrentItemWithPlayerItem(ready.item)
            // its own player comes on screen, from the start (as YouTube plays a Short again); one that never played is
            // there already, and a seek would throw away what it buffered
            if (secs(ready.item.currentTime()) > 0.05) ready.item.seekToTime(CMTimeMake(0, 1000))
            frames = ready.out
            swapTo(ready.player, old)
        } else {
            val (item, out) = makeItem(s, o) ?: return
            frames = out
            // the one going away stays with its item for a swipe back: this one gets a player of its own
            if (park) swapTo(idlePlayer(), old)
            av.replaceCurrentItemWithPlayerItem(item)
        }
        // the Short swiped away waits, ready, for a swipe back; anything else lets the waiting ones go
        if (park) {
            old.pause()
            old.muted = true
            keepWarm(Warm(before!!, beforeItem!!, beforeOut!!, old))
            // the one swiped away waits small too
            beforeItem.preferredForwardBufferDuration = WARM_AHEAD
        } else {
            if (av !== old) { old.pause(); old.replaceCurrentItemWithPlayerItem(null) }
            if (!o.keepReady) dropWarm()
        }
        val hls = s.hls ?: return
        gain()
        audioPicked = false
        startAt = o.startMs
        st.value = PlayerState(videoId = s.id, buffering = true, positionMs = o.startMs, durationMs = s.lengthSec * 1000L,
            speed = o.speed, captions = o.captions)
        audioWanted = o.audioLang
        val item = av.currentItem ?: return
        // the sound is chosen as soon as the list is read, before AVPlayer has played (or, offline, waited for) the first one in it
        item.asset.loadValuesAsynchronouslyForKeys(listOf("availableMediaCharacteristicsWithMediaSelectionOptions")) {
            dispatch_async(dispatch_get_main_queue()) {
                if (av.currentItem === item && !audioPicked) { audioPicked = true; pickAudio(item) }
            }
        }
        if (o.autoplay) av.playImmediatelyAtRate(o.speed) else av.pause()
        firstFrame(unlimit = ready?.item)
        // the qualities on offer, from the HLS master list
        variants = emptyMap()
        heightsJob?.cancel()
        heightsJob = scope.launch {
            if (s.client == "offline") return@launch
            val text = runCatching { Net.get(hls) }.getOrNull() ?: return@launch
            val heights = Regex("RESOLUTION=\\d+x(\\d+)").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted().toList()
            // each variant's codec and frame rate by its itag (its list's address names it), for now()
            variants = Regex("#EXT-X-STREAM-INF:([^\\n]*)\\n([^\\n]+)").findAll(text).mapNotNull { m ->
                val itag = Regex("/itag/(\\d+)").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val codec = Regex("CODECS=\"([^\",]+)").find(m.groupValues[1])?.groupValues?.get(1) ?: ""
                val fps = Regex("FRAME-RATE=([\\d.]+)").find(m.groupValues[1])?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0
                itag to (codec to fps)
            }.toMap()
            st.update { it.copy(heights = heights) }
        }
        val translate = if (o.captions != null && o.captionLang != null && o.captions.lang.substringBefore('-') != o.captionLang) o.captionLang else null
        captions(o.captions, translate)
    }

    /**
     * The sound to play: [audioWanted], else the original (app.tubeless.ui.audioOption). YouTube's HLS lists mark
     * none as the default, and with the automatic choice off AVPlayer took the first, an Arabic dub. A saved video
     * chooses only among the sounds in its package: one that isn't there AVPlayer fetches from YouTube (a saved
     * video streamed), or, offline, waits for for ever. The tracks go to the player sheet as language to name.
     */
    private fun pickAudio(item: AVPlayerItem) {
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) ?: return savedSound(item)
        val all = group.options.filterIsInstance<AVMediaSelectionOption>()
        val saved = if (stream?.client != "offline") null
            else (item.asset as? AVURLAsset)?.assetCache?.mediaSelectionOptionsInMediaSelectionGroup(group)?.filterIsInstance<AVMediaSelectionOption>()
        val options = saved?.ifEmpty { null } ?: all
        if (options.isEmpty()) return
        val audio = stream?.audio.orEmpty()
        val pick = app.tubeless.ui.audioOption(options, audioWanted, audio) ?: group.defaultOption?.takeIf { it in options } ?: options.first()
        // the same one again (chosen while it waited, see preload) is left alone: a new choice can throw away the buffer
        if (item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(group) != pick) item.selectMediaOption(pick, inMediaSelectionGroup = group)
        fun key(o: AVMediaSelectionOption): String = o.extendedLanguageTag.let { t -> audio.firstOrNull { it.audioLang.equals(t, true) }?.audioLang ?: t ?: "" }
        val tracks = options.map { o -> key(o) to (audio.firstOrNull { it.audioLang.equals(key(o), true) }?.audioTrackName ?: app.tubeless.ui.hlsName(o)) }
            .filter { it.first.isNotEmpty() }.distinctBy { it.first }
        st.update { it.copy(audioTracks = if (tracks.size > 1) tracks else emptyList(), audioTrack = key(pick).ifEmpty { null }) }
        app.tubeless.ui.E2E.log("audio_pick", "videoId" to (stream?.id ?: ""), "lang" to key(pick), "name" to app.tubeless.ui.hlsName(pick),
            "offline" to (saved != null), "choices" to options.size)
    }

    /** a saved video's one sound (its .mp4 has a single audio track, in the language chosen when it was saved) */
    private fun savedSound(item: AVPlayerItem) {
        val s = stream ?: return
        if (s.client != "offline") return
        val a = s.audio.firstOrNull()
        val lang = a?.audioLang ?: ""
        val track = item.asset.tracksWithMediaType(platform.AVFoundation.AVMediaTypeAudio).firstOrNull() as? platform.AVFoundation.AVAssetTrack
        st.update { it.copy(audioTracks = emptyList(), audioTrack = lang.ifEmpty { null }) }
        app.tubeless.ui.E2E.log("audio_pick", "videoId" to s.id, "lang" to lang, "name" to (a?.audioTrackName ?: ""), "offline" to true, "choices" to 1,
            "fileLang" to (track?.extendedLanguageTag ?: track?.languageCode ?: ""))
    }

    override fun setAudioTrack(lang: String) {
        audioWanted = lang
        val item = av.currentItem ?: return
        if (audioPicked) pickAudio(item)
    }

    /**
     * Auto (QUALITY): the height that fills this screen turned sideways ([Quality.fit] on the native pixels: 1440p on
     * a recent iPhone, which AVPlayer reaches with YouTube's VP9 variants), AVPlayer adapting below it; a height
     * picked by hand as it is
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun auto(cap: Int, s: Stream? = stream): Int {
        if (cap != app.tubeless.engine.Quality.AUTO) return cap
        val (w, h) = platform.UIKit.UIScreen.mainScreen.nativeBounds.useContents { size.width.toInt() to size.height.toInt() }
        val sizes = s?.video.orEmpty().map { it.width to it.height }.distinct()
        return app.tubeless.engine.Quality.fit(w, h, sizes).takeIf { it > 0 } ?: 0
    }

    /** the highest quality AVPlayer may pick; 0 = no limit (it adapts to the connection) */
    private fun limit(item: AVPlayerItem, h: Int) {
        item.preferredMaximumResolution = if (h in 1..4319) CGSizeMake(h * 16.0 / 9.0, h.toDouble()) else CGSizeMake(0.0, 0.0)
    }

    private var frames: platform.AVFoundation.AVPlayerItemVideoOutput? = null
    private val ci by lazy { platform.CoreImage.CIContext.contextWithOptions(null) }

    /**
     * Ambient mode (as Android and the desktop): the picture now, 32×18 and blurred, from the item's
     * video output through Core Image; null before the first frame.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override suspend fun frame(): androidx.compose.ui.graphics.ImageBitmap? {
        val out = frames ?: return null
        val t = av.currentTime()
        val buf = out.copyPixelBufferForItemTime(t, itemTimeForDisplay = null) ?: return null
        try {
            val img = platform.CoreImage.CIImage.imageWithCVPixelBuffer(buf)
            val ext = img.extent
            val w = ext.useContents { size.width }
            val h = ext.useContents { size.height }
            if (w <= 0.0 || h <= 0.0) return null
            val small = img.imageByApplyingTransform(platform.CoreGraphics.CGAffineTransformMakeScale(32.0 / w, 18.0 / h))
                .imageByApplyingGaussianBlurWithSigma(1.5)
            val px = ByteArray(32 * 18 * 4)
            px.usePinned { pin ->
                ci.render(small, toBitmap = pin.addressOf(0), rowBytes = 32 * 4,
                    bounds = platform.CoreGraphics.CGRectMake(0.0, 0.0, 32.0, 18.0),
                    format = platform.CoreImage.kCIFormatRGBA8, colorSpace = null)
            }
            val bmp = org.jetbrains.skia.Bitmap()
            bmp.allocPixels(org.jetbrains.skia.ImageInfo(32, 18, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL))
            bmp.installPixels(px)
            return bmp.asComposeImageBitmap()
        } finally {
            platform.CoreVideo.CVPixelBufferRelease(buf)
        }
    }

    override fun play() {
        if (st.value.ended) av.seekToTime(CMTimeMake(0, 1000))
        av.playImmediatelyAtRate(opts.speed)
        st.update { it.copy(playing = true, ended = false) }
    }

    override fun pause() {
        av.pause()
        st.update { it.copy(playing = false) }
    }

    override fun seekTo(ms: Long) {
        val to = ms.coerceAtLeast(0)
        if (startAt >= 0) startAt = to else av.seekToTime(CMTimeMake(to, 1000))
        st.update { it.copy(positionMs = to, ended = false) }
    }

    /**
     * How the sound keeps its pitch at a speed. Spectral (a phase vocoder) smears voices into an echo, which is what
     * people heard at 1.25-2x; time-domain (WSOLA, what YouTube's player does) keeps speech dry and natural up to 2x.
     * Past that, where time-domain starts to stutter, spectral is the better of the two.
     */
    private fun pitchFor(speed: Float) =
        if (speed <= 2f) platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain else platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral

    override fun setSpeed(x: Float) {
        av.currentItem?.audioTimePitchAlgorithm = pitchFor(x)
        opts = opts.copy(speed = x)
        if (av.rate > 0f) av.rate = x
        st.update { it.copy(speed = x) }
    }

    override fun setHeight(h: Int) {
        if (stream?.client != "offline") av.currentItem?.let { limit(it, if (h == 0) auto(opts.maxHeight) else h) }
        st.update { it.copy(height = h) }
    }

    override fun setCaptions(c: Caption?, translateTo: String?) {
        st.update { it.copy(captions = c) }
        captions(c, translateTo)
    }

    private fun captions(c: Caption?, translateTo: String?) {
        capsJob?.cancel()
        cues = emptyList()
        if (c == null) return
        capsJob = scope.launch {
            val text = runCatching { Net.get(YouTube.captionUrl(c, translateTo)) }.getOrNull() ?: return@launch
            cues = parseVtt(text)
        }
    }

    override fun setLoop(on: Boolean) { opts = opts.copy(loop = on) }

    /** a feed card's muted autoplay (FeedPlay); the phone's own volume stays the system's */
    override fun setMuted(on: Boolean) {
        av.muted = on
        st.update { it.copy(muted = on) }
    }

    override fun setReadAhead(long: Boolean) {
        // 0 lets AVPlayer choose (a few seconds to a minute)
        readAhead = long
        av.currentItem?.preferredForwardBufferDuration = if (long) 1200.0 else 0.0
    }

    override fun stop() {
        av.pause()
        av.replaceCurrentItemWithPlayerItem(null)
        dropWarm()
        heightsJob?.cancel(); capsJob?.cancel()
        stream = null
        startAt = -1
        cues = emptyList()
        st.value = PlayerState()
    }

    @Composable
    override fun View(modifier: Modifier) {
        val s by st.collectAsState()
        BoxWithConstraints(modifier.background(Color.Black)) {
            // the picture takes no touches: they go to the Compose behind and over it (a feed card's tap opens the
            // video it plays muted; in the default cooperative mode the interop view swallowed that tap)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            UIKitView(factory = { PlayerHost(::hostMoved) }, modifier = Modifier.fillMaxSize(),
                properties = androidx.compose.ui.viewinterop.UIKitInteropProperties(interactionMode = null))
            // none in the mini player, as YouTube: at its size a line of captions covers the whole picture
            val line = if (s.captions != null && maxHeight >= 150.dp) cues.lastOrNull { s.positionMs >= it.start && s.positionMs < it.end }?.text else null
            // sized to the player like Android's (about 1/19 of its height): a fixed size was tiny on an iPad in full screen
            val (pct, bg, pos) = capStyle
            val base = (maxHeight.value / 19f).coerceIn(16f, 44f)
            val size = base * pct / 100f
            // no box: a soft shadow keeps white text readable on a bright picture
            val style = if (bg == 0) TextStyle(shadow = Shadow(Color.Black, Offset(0f, 1f), 6f)) else TextStyle.Default
            val place = when (pos) {
                2 -> Modifier.align(Alignment.TopCenter).padding(start = 24.dp, end = 24.dp, top = (base * 1.4f).dp)
                1 -> Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = (maxHeight.value * 0.2f).dp)
                else -> Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = (base * 1.4f).dp)
            }
            if (line != null) Text(
                line, color = Color.White, fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center, style = style,
                modifier = place.background(Color.Black.copy(alpha = bg / 100f)).padding(horizontal = (size / 2).dp, vertical = 2.dp),
            )
        }
    }
}

/** a place for the player's picture; it tells the player when it comes onto the screen or leaves it, and when it changes size */
private class PlayerHost(private val moved: (PlayerHost, Boolean) -> Unit) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        moved(this, window != null)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // only when the size really changed: setting a frame lays things out again
        subviews.forEach { v -> (v as? UIView)?.takeIf { !CGRectEqualToRect(it.frame, bounds) }?.setFrame(bounds) }
    }
}

/**
 * The picture: an AVPlayerLayer that follows the view's size; taps go through to the controls.
 * Going to the home screen while it plays moves the video into a picture-in-picture window.
 */
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val picture = AVPlayerLayer.playerLayerWithPlayer(player)

    /** another player's picture in the same layer (and the same picture-in-picture controller) */
    fun show(p: AVPlayer) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.player = p
        CATransaction.commit()
    }
    val pip: AVPictureInPictureController? =
        if (AVPictureInPictureController.isPictureInPictureSupported()) AVPictureInPictureController(playerLayer = picture) else null

    init {
        backgroundColor = UIColor.blackColor
        userInteractionEnabled = false
        picture.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(picture)
        pip?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        if (CGRectEqualToRect(picture.frame, bounds)) return
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.frame = bounds
        CATransaction.commit()
    }
}

private class Cue(val start: Long, val end: Long, val text: String)

/** cues of a cleaned WebVTT (Vtt.clean: no tags, no overlaps) */
private fun parseVtt(src: String): List<Cue> {
    val out = ArrayList<Cue>()
    val rows = src.replace("\r\n", "\n").split('\n')
    var i = 0
    fun ms(t: String): Long {
        val p = t.trim().split(':')
        val (h, m, s) = if (p.size == 3) Triple(p[0].toLong(), p[1].toLong(), p[2]) else Triple(0L, p[0].toLong(), p[1])
        return (h * 3600 + m * 60) * 1000 + (s.toDouble() * 1000).toLong()
    }
    while (i < rows.size) {
        val row = rows[i]
        if ("-->" !in row) { i++; continue }
        val a = row.substringBefore("-->")
        val b = row.substringAfter("-->").trim().substringBefore(' ')
        i++
        val text = StringBuilder()
        while (i < rows.size && rows[i].isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(rows[i].trim()); i++ }
        runCatching { out += Cue(ms(a), ms(b), text.toString()) }
    }
    return out
}
