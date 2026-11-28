package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tubeless.engine.Video
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * YouTube's "Playback in feeds" on phones: the top card of a feed that is at least half on screen plays,
 * muted, after about a second; a tap opens the video where it got to, with sound. The app's one player
 * does it while nothing else plays (no second decoder, and opening the card picks up the same stream).
 * Settings → Display → "Playback in feeds": Always on / Wi-Fi only / Off.
 */
object FeedPlay {
    /** the card playing now (its picture shows the video); null: none */
    var id by mutableStateOf<String?>(null)
        private set
    /** the sound turned on at the card's speaker button */
    var withSound by mutableStateOf(false)
        private set
    private var job: Job? = null
    private var owner: Any? = null

    /** may a feed play its top card now: the setting, the connection, nothing else playing */
    fun allowed(app: AppState): Boolean {
        val s = app.store.settings.value
        if (!app.platform.touch || app.platform.tv || s.hideThumbs || s.kids || s.calm) return false
        if (app.playback.video != null || app.casting.link != null) return false
        return when (s.feedPlayback) { 0 -> true; 1 -> !app.platform.metered(); else -> false }
    }

    /** plays [v] in its card (muted), in place of what played before; [from] is the feed asking */
    fun play(app: AppState, v: Video, from: Any) {
        if (id == v.id && owner === from) return
        stop(app)
        owner = from
        id = v.id
        withSound = false
        job = app.scope.launch {
            val s = runCatching { app.offline.stream(v.id) ?: Streams.get(app.scope, v.id) }.getOrNull() ?: run { if (id == v.id) id = null; return@launch }
            if (id != v.id || app.playback.video != null) return@launch
            app.player.setMuted(true)
            app.player.load(s, PlayOptions(maxHeight = 360, autoplay = true, volume = 0f, title = s.title, artist = s.author, audioLang = app.playback.audioLangFor(s)))
            app.player.setMuted(true)
        }
    }

    /** the feed's card stops (scrolled away, the page left, the app in the background) */
    fun stop(app: AppState, from: Any? = null) {
        if (from != null && owner !== from) return
        job?.cancel(); job = null
        val was = id
        id = null; owner = null
        if (was != null && app.playback.video == null) {
            app.player.stop()
            app.player.setMuted(false)
            app.player.setVolume(app.store.settings.value.volume)
        }
    }

    fun toggleSound(app: AppState) {
        withSound = !withSound
        app.player.setMuted(!withSound)
        if (withSound) app.player.setVolume(app.store.settings.value.volume)
    }

    /**
     * The video page opens [videoId]: the card's playback hands over. The point it reached when it was
     * this video (to go on from there, as YouTube does), else null; the sound is back on either way.
     */
    fun handOver(app: AppState, videoId: String): Long? {
        val was = id ?: return null
        job?.cancel(); job = null
        id = null; owner = null
        val st = app.player.state.value
        val at = if (was == videoId && st.videoId == videoId && st.positionMs > 1500) st.positionMs else null
        app.player.setMuted(false)
        app.player.setVolume(app.store.settings.value.volume)
        return at
    }
}

/** one card on screen: its list key, where its top is and how wide it is (its picture is the top 16:9) */
class FeedSlot(val key: Any, val top: Int, val width: Int)

/**
 * Follows a phone feed's list: the first video card whose picture is at least half on screen plays in
 * its picture after a second still; scrolling on, leaving the page or another video starting stops it.
 */
@Composable
fun FeedAutoplay(app: AppState, state: LazyGridState, videoFor: (Any) -> Video?) =
    FeedAutoplay(app, state, { state.isScrollInProgress }, {
        val i = state.layoutInfo
        Triple(i.viewportStartOffset, i.viewportEndOffset, i.visibleItemsInfo.map { FeedSlot(it.key, it.offset.y, it.size.width) })
    }, videoFor)

@Composable
fun FeedAutoplay(app: AppState, state: androidx.compose.foundation.lazy.LazyListState, videoFor: (Any) -> Video?) =
    FeedAutoplay(app, state, { state.isScrollInProgress }, {
        val i = state.layoutInfo
        // a column's rows are as wide as the list
        val w = i.viewportSize.width
        Triple(i.viewportStartOffset, i.viewportEndOffset, i.visibleItemsInfo.map { FeedSlot(it.key, it.offset, w) })
    }, videoFor)

@Composable
private fun FeedAutoplay(app: AppState, key: Any, scrolling: () -> Boolean, slots: () -> Triple<Int, Int, List<FeedSlot>>, videoFor: (Any) -> Video?) {
    val at by androidx.compose.runtime.rememberUpdatedState(videoFor)
    DisposableEffect(key) { onDispose { FeedPlay.stop(app, key) } }
    val settings by app.store.settings.state.collectAsState()
    LaunchedEffect(key, settings.feedPlayback) {
        snapshotFlow {
            // while a video plays (the watch page, the mini player) nothing here; once it closes the card plays again
            if (scrolling() || app.playback.video != null) return@snapshotFlow null
            val (top, bottom, list) = slots()
            list.firstNotNullOfOrNull { item ->
                val v = at(item.key) ?: return@firstNotNullOfOrNull null
                if (v.live || v.short || v.lengthSec in 1..3) return@firstNotNullOfOrNull null
                val picH = item.width * 9 / 16
                val a = maxOf(item.top, top)
                val b = minOf(item.top + picH, bottom)
                v.takeIf { picH > 0 && (b - a) * 2 >= picH }
            }
        }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { v ->
            if (v == null) { FeedPlay.stop(app, key); return@collect }
            delay(1000)
            if (FeedPlay.allowed(app)) FeedPlay.play(app, v, key) else FeedPlay.stop(app, key)
        }
    }
}

/** over a card's picture while it plays: the video, the speaker button, the red line of how far it got */
@Composable
fun BoxScope.FeedPlayLayer(app: AppState, v: Video) {
    if (FeedPlay.id != v.id) return
    val st by app.player.state.collectAsState()
    // the picture stays until the video moves: a position alone can come before the first frame (a black box)
    var moving by androidx.compose.runtime.remember(v.id) { mutableStateOf(false) }
    if (st.videoId != v.id) { moving = false; return }
    if (st.playing && st.positionMs > 0) moving = true
    if (!moving) return
    app.player.View(Modifier.matchParentSize())
    // a tap anywhere opens it (the card's own click), the speaker toggles the sound
    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x99000000))
        .clickable { FeedPlay.toggleSound(app) }, contentAlignment = Alignment.Center) {
        Icon(if (FeedPlay.withSound) Sym.VolumeUp.fill else Sym.VolumeOff.fill, tr(if (FeedPlay.withSound) "Mute" else "Unmute"),
            Modifier.size(20.dp), tint = Color.White)
    }
    if (st.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).background(Red))
    }
}
