package app.tubeless.ui

import app.tubeless.engine.Ads
import app.tubeless.engine.Quality
import app.tubeless.engine.ReelSequence
import app.tubeless.engine.Stream
import app.tubeless.engine.Sys
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shorts ready before the tab is opened, as in YouTube's app: once Home has its first page, the start of a Shorts
 * sequence is fetched, the streams of its first few are resolved, the first one buffers in a muted, paused player
 * (where the platform has one, see [VideoPlayer.preload] with [PlayOptions.standby]) and their pictures are put in
 * the image cache. Opening Shorts then plays the first one at once ([ready]/[take]). Home's pictures are fetched
 * ahead too ([thumbs]). One batch waits at a time; it is used once, and dropped after [STALE_MS].
 */
object ShortsWarm {
    class Batch(val seq: ReelSequence, val reels: List<ReelSequence.Reel>, val streams: Map<String, Stream>, val at: Long)

    private const val STALE_MS = 30 * 60_000L
    /** Shorts whose streams are resolved ahead; the first of them is also buffered */
    private const val RESOLVE = 3
    private var job: Deferred<Batch?>? = null

    /** the screen in pixels, for the Shorts pictures (set by Home, [Warmup]) */
    var screen: Pair<Int, Int> = 0 to 0

    /** how a Short plays: looped, at the quality setting, kept ready for a swipe back (ShortsScreen) */
    fun options(app: AppState, v: Video, s: Stream): PlayOptions {
        // Auto as every video (QUALITY): a Short is upright, so its 1080p is 1920 tall; the old cap of 720 played it at 404×720
        val cap = app.store.settings.value.let { st -> if (app.platform.metered()) st.maxHeightMobileData else st.maxHeight }
        return PlayOptions(maxHeight = if (cap <= 0) Quality.AUTO else cap, loop = true, volume = app.store.settings.value.volume,
            title = s.title, artist = s.author, artwork = v.thumb, keepReady = true, audioLang = app.playback.audioLangFor(s))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun completed(): Batch? = job?.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { it.getCompleted() }.getOrNull() }

    /**
     * Gets a batch ready in the background, unless one is waiting or on its way. Low priority: it starts after Home's
     * own first pictures have had a moment, and does nothing on Shorts itself or with Shorts turned off.
     */
    fun start(app: AppState, ctx: PlatformContext) {
        if (app.noShorts || app.current is Screen.Shorts) return
        val j = job
        if (j != null && j.isActive) return
        completed()?.let { if (Sys.nowMs() - it.at < STALE_MS) return }
        job = app.scope.async {
            delay(700)
            runCatching { fetch(app, ctx) }.getOrNull()
        }
    }

    private suspend fun fetch(app: AppState, ctx: PlatformContext): Batch? = coroutineScope {
        val seq = ReelSequence()
        val hidden = app.store.taste.value.hiddenVideos
        val reels = seq.start(null).filter { it.video.id !in hidden && !Ads.isAd(it.video.id) }.distinctBy { it.video.id }
        if (reels.isEmpty()) return@coroutineScope null
        val first = reels.take(RESOLVE)
        // their pictures: the first one decoded at the screen's size (shown the moment Shorts opens), the others on disk
        val (w, h) = screen
        first.forEachIndexed { i, r -> picture(ctx, r.video.thumb, w, h, memory = i == 0) }
        val streams = first.map { r -> async { r.video.id to runCatching { YouTube.stream(r.video.id) }.getOrNull() } }.awaitAll()
            .mapNotNull { (id, s) -> s?.let { id to it } }.toMap()
        // the first one buffers in a muted, paused player that waits for the Shorts tab
        val v0 = first[0].video
        streams[v0.id]?.let { s -> if (app.current !is Screen.Shorts) app.player.preload(s, options(app, v0, s).copy(standby = true)) }
        E2E.log("shorts_warm", "reels" to reels.size, "streams" to streams.size, "first" to v0.id)
        Batch(seq, reels, streams, Sys.nowMs())
    }

    /** the waiting batch, if it is there already (used once) */
    fun ready(): Batch? {
        val b = completed() ?: return null
        job = null
        return b.takeIf { Sys.nowMs() - it.at < STALE_MS }
    }

    /** the batch, waiting a few seconds for one on its way (used once); null: start a sequence of your own */
    suspend fun take(): Batch? {
        val j = job ?: return null
        val b = withTimeoutOrNull(6_000) { j.await() }
        job = null
        return b?.takeIf { Sys.nowMs() - it.at < STALE_MS }
    }

    /** a picture into the image cache at the size it will be shown ([Thumbs] picks the file by that size) */
    fun picture(ctx: PlatformContext, url: String, w: Int, h: Int, memory: Boolean = false) {
        if (url.isEmpty()) return
        val r = ImageRequest.Builder(ctx).data(url)
            .let { if (w > 0 && h > 0) it.size(w, h) else it }
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()
        SingletonImageLoader.get(ctx).enqueue(r)
    }

    private val fetched = HashSet<String>()

    /**
     * Home's pictures ahead of the scroll: every video of the page (and of each next page when it comes) into the disk
     * cache, in order and four at a time (the screen's own pictures keep the connection), at a card's size so [Thumbs]
     * asks the same file the card will (any box wider than an icon gets hq720).
     */
    suspend fun thumbs(ctx: PlatformContext, videos: List<Video>) {
        if (fetched.size > 2000) fetched.clear()
        val loader = SingletonImageLoader.get(ctx)
        for (part in videos.map { it.thumb }.filter { it.isNotEmpty() && it !in fetched }.chunked(4)) coroutineScope {
            part.map { url ->
                async {
                    val r = ImageRequest.Builder(ctx).data(url).size(1280, 720).memoryCachePolicy(CachePolicy.DISABLED).build()
                    loader.execute(r)
                    fetched.add(url)
                }
            }.awaitAll()
        }
    }
}

/**
 * Home's warm-up, after its first page: the next pictures, and Shorts ready for the tab ([ShortsWarm]).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
fun Warmup(app: AppState, videos: List<Video>) {
    val ctx = coil3.compose.LocalPlatformContext.current
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    ShortsWarm.screen = win.width to win.height
    val hide = app.store.settings.value.hideThumbs
    androidx.compose.runtime.LaunchedEffect(videos.size) {
        // after the first screen's own pictures have started
        delay(400)
        ShortsWarm.start(app, ctx)
        if (!hide) ShortsWarm.thumbs(ctx, videos)
    }
}
