package app.tubeless.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import app.tubeless.engine.YouTube
import app.tubeless.engine.Sys
import app.tubeless.engine.DeArrow
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.Channel
import app.tubeless.engine.Item
import app.tubeless.engine.Page
import app.tubeless.engine.Playlist
import app.tubeless.engine.Video
import app.tubeless.rec.Learn
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * TV: the remote moves a focus from card to card, and the selected one has to show from across
 * the room: it gets a teal frame and grows a little. Elsewhere this does nothing.
 */
@Composable
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    if (!LocalPlatform.current.tv) return this
    var on by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (on) 1.04f else 1f, tween(Motion.SHORT, easing = Motion.standard))
    return this.graphicsLayer { scaleX = grow; scaleY = grow }
        .onFocusChanged { on = it.hasFocus }
        .let { if (on) it.border(3.dp, Teal, shape) else it }
}

/**
 * clickable, and on a TV with [tvFocus]'s frame instead of a tint over the whole card;
 * [onLongClick] is holding OK on the remote (the card's ⋮ menu). On a touch screen or with a mouse
 * it is pressed as in YouTube's app: the card sinks a little and a soft grey shape comes up behind
 * it (no ripple), quickly in and slowly out.
 */
@Composable
fun Modifier.tvClickable(shape: Shape = RoundedCornerShape(12.dp), onLongClick: (() -> Unit)? = null, sink: Float = 0.97f, onClick: () -> Unit): Modifier {
    if (!LocalPlatform.current.tv) {
        val touch = remember { MutableInteractionSource() }
        val pressed by touch.collectIsPressedAsState()
        // let go: a button springs back (the web's buttons), a card settles
        val sink by animateFloatAsState(if (pressed) sink else 1f,
            if (pressed) tween(Motion.SHORT, easing = Motion.standard) else if (sink < 0.96f) spring(0.5f, Spring.StiffnessMedium) else tween(Motion.MEDIUM, easing = Motion.standard))
        val shade by animateFloatAsState(if (pressed) 0.1f else 0f, tween(if (pressed) 90 else Motion.LONG, easing = Motion.standard))
        val ink = MaterialTheme.colorScheme.onSurface
        return graphicsLayer { scaleX = sink; scaleY = sink }
            .drawBehind { if (shade > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), ink.copy(alpha = shade)) }
            .combinedClickable(touch, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
    }
    return tvFocus(shape).combinedClickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
}

/**
 * A chip as the web app 1.0 draws them: a pill, the chosen one filled with the text color (white on
 * dark, black on light), the others a quiet grey. Home, Subscriptions' groups, search, comments.
 */
@Composable
fun Chip(selected: Boolean, onClick: () -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    // YouTube's chips (app and website alike): 32dp tall, 8dp corners
    val shape = RoundedCornerShape(8.dp)
    // the colours turn over smoothly when it is chosen
    val bg by animateColorAsState(if (selected) c.onSurface else c.surfaceVariant, tween(Motion.SHORT))
    val fg by animateColorAsState(if (selected) c.surface else c.onSurface, tween(Motion.SHORT))
    Row(
        Modifier.height(32.dp).tvClickable(shape, sink = 0.94f, onClick = onClick).semantics { this.selected = selected }.clip(shape).background(bg)
            .padding(horizontal = if (icon != null && label.isEmpty()) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        if (label.isNotEmpty()) Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** on a TV the remote skips this (a button inside a card, reached by holding OK instead) */
@Composable
fun Modifier.notOnTv(): Modifier = if (LocalPlatform.current.tv) focusProperties { canFocus = false } else this

@Composable
fun Img(url: String, modifier: Modifier = Modifier, crop: Boolean = true) {
    // a Windows file URI (file:/C:/...: a saved video's thumbnail) isn't a path the image loader can open there:
    // its bytes are read here instead (QA-CI: Downloads showed grey boxes on Windows only)
    val drive = remember(url) { windowsFile(url) }
    val bytes = if (drive == null) null else androidx.compose.runtime.produceState<ByteArray?>(null, drive) {
        value = kotlinx.coroutines.withContext(Sys.IO) { runCatching { Sys.fs.read(with(okio.Path) { drive.toPath() }) { readByteArray() } }.getOrNull() }
    }.value
    AsyncImage(
        model = if (drive != null) bytes else url.ifEmpty { null },
        contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
    )
}

/** "file:/C:/My%20Videos/x.jpg" → "C:/My Videos/x.jpg"; null for anything else */
internal fun windowsFile(url: String): String? {
    val m = Regex("^file:/+([A-Za-z]:/.*)$").find(url) ?: return null
    val raw = m.groupValues[1]
    val out = StringBuilder()
    val bytes = ArrayList<Byte>()
    var i = 0
    fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length && raw.substring(i + 1, i + 3).toIntOrNull(16) != null) {
            bytes.add(raw.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
        } else { flush(); out.append(c); i++ }
    }
    flush()
    return out.toString()
}

@Composable
fun Avatar(url: String, size: Int = 36, modifier: Modifier = Modifier) {
    Img(url, modifier.size(size.dp).clip(CircleShape))
}

/** LIVE, SHORTS or the length: on the thumbnail, or before the details when thumbnails are off */
@Composable
fun Video.badge(): String = when {
    live -> tr("LIVE")
    short -> tr("SHORTS")
    else -> duration(lengthSec)
}

/** The line under a title; with thumbnails switched off it starts with the length they would show. */
@Composable
fun Video.details(app: AppState, calm: Boolean): String =
    listOf(badge().takeIf { app.store.settings.value.hideThumbs } ?: "", subline(calm)).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * A channel's picture for a card whose list came without one (YouTube leaves it out of many lists):
 * a subscription's, else the one its channel page shows, asked once and kept on the device.
 */
@Composable
fun channelAvatar(app: AppState, channelId: String, given: String): String {
    if (given.isNotEmpty() || channelId.isEmpty()) return given
    app.store.lib.value.subs[channelId]?.thumb?.takeIf { it.isNotEmpty() }?.let { return it }
    val known = app.store.avatars.state.collectAsState().value[channelId]
    if (known != null) return known
    LaunchedEffect(channelId) { ChannelAvatars.fetch(app, channelId) }
    return ""
}

private object ChannelAvatars {
    private val asking = Sys.concurrentMap<String, Boolean>()
    /** a home page of 40 cards asks a few channels at a time */
    private val gate = Semaphore(3)

    suspend fun fetch(app: AppState, id: String) {
        if (asking.containsKey(id)) return
        asking[id] = true
        try {
            val url = gate.withPermit { YouTube.channel(id).avatar }
            if (url.isNotEmpty()) app.store.avatars.update { m -> (if (m.size > 3000) m.entries.drop(500).associate { it.key to it.value } else m) + (id to url) }
        } catch (e: CancellationException) {
            asking.remove(id)      // the card went away: the next one showing this channel asks again
            throw e
        } catch (_: Exception) {
            // offline or YouTube said no: leave the circle empty until the app opens again
        }
    }
}

/** DeArrow's title and thumbnail for [v] while switched on: asked once per video, then kept */
@Composable
fun branded(v: Video, app: AppState): DeArrow.Brand? {
    if (!app.store.settings.value.dearrow || v.short || v.id.isEmpty()) return null
    var b by remember(v.id) { mutableStateOf(DeArrow.cached(v.id)) }
    LaunchedEffect(v.id) { if (b == null) b = DeArrow.brand(v.id) }
    return b
}

/** the title to show: DeArrow's when it has one */
@Composable
fun titleOf(v: Video, app: AppState): String = branded(v, app)?.title?.ifEmpty { null } ?: v.title

/** Thumbnail with the length badge and a red bar for how much you've watched. */
@Composable
fun Thumb(v: Video, app: AppState, modifier: Modifier = Modifier, rounded: Int = 16, feedPlay: Boolean = true) {
    val pos = app.store.lib.state.value.pos[v.id]
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(rounded.dp))) {
        if (v.author.endsWith("- Topic") || v.author.endsWith("- 주제")) {
            // a song's picture is its square cover in grey bars: the cover sharp in the middle, a blur of it behind
            // (YouTube's 16:9 picture of it: the cover is its middle square; the 4:3 one has bars all round)
            val art = "https://i.ytimg.com/vi/" + v.id + "/mqdefault.jpg"
            Img(art, Modifier.fillMaxSize().graphicsLayer { scaleX = 2.2f; scaleY = 2.2f }.blur(28.dp))
            Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            Img(art, Modifier.fillMaxHeight().aspectRatio(1f).align(Alignment.Center))
        } else Img(v.thumb, Modifier.fillMaxSize())
        // DeArrow's frame laid over YouTube's picture: it shows once it has loaded, and not at all if it can't
        val sec = branded(v, app)?.timeSec?.takeIf { app.store.settings.value.dearrowThumbs && !v.live }
        if (sec != null) AsyncImage(DeArrow.thumbUrl(v.id, sec), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val label = v.badge()
        if (label.isNotEmpty()) {
            Text(
                label,
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(if (rounded >= 12) 8.dp else 4.dp)
                    .background(if (v.live) Red else Color(0xB3000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // a phone feed's top card playing muted (FeedPlay)
        // ([feedPlay] off: a row that never plays, such as Continue watching, keeps its picture when the feed plays the same video)
        if (app.platform.touch && feedPlay) FeedPlayLayer(app, v)
        if (pos != null && (pos.getOrNull(1) ?: 0) > 0 && (FeedPlay.id != v.id || !feedPlay)) {
            val f = (pos[0].toFloat() / pos[1]).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(Red))
            }
        }
    }
}

/** The ⋮ menu of a video: save, add to playlist, not interested, share. */
@Composable
fun VideoMenu(v: Video, app: AppState, extra: List<Pair<String, () -> Unit>> = emptyList(), openState: MutableState<Boolean>? = null) {
    val opener = openState ?: remember { mutableStateOf(false) }
    var open by opener
    var pick by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).notOnTv()) {
            Icon(Sym.MoreVert.fill, tr("More"), modifier = Modifier.size(18.dp))
        }
        // YouTube's order: Play next, Watch later, Save to playlist, Download, Share, then Not interested and Don't recommend
        val inWl = app.store.inList("wl", v.id)
        val pb = app.playback
        val rows = buildList<Triple<androidx.compose.ui.graphics.vector.ImageVector?, String, () -> Unit>> {
            // while a video plays: line this one up after it (#59)
            if (pb.video != null && !pb.music && pb.video?.id != v.id) {
                add(Triple(Sym.QueueMusic.outline, tr("Play next")) { pb.queueNext(v); app.toast(tr("Plays next")) })
                add(Triple(Sym.QueueMusic.outline, tr("Add to queue")) { pb.queueAdd(v); app.toast(tr("Added to the queue")) })
            }
            add(Triple(Sym.WatchLater.outline, tr(if (inWl) "Remove from Watch later" else "Save to Watch later")) { app.store.setInList("wl", v, !inWl) })
            add(Triple(Sym.LibraryAdd.outline, tr("Save to playlist")) { pick = true })
            // saved on this device to watch without internet
            when {
                app.offline.has(v.id) -> add(Triple(Sym.DownloadDone.outline, tr("Remove download")) { app.offline.remove(v.id) })
                app.offline.downloading(v.id) -> add(Triple(Sym.Downloading.outline, tr("Cancel download")) { app.offline.cancel(v.id) })
                !v.live && app.platform.canDownload -> add(Triple(Sym.Download.outline, tr("Download")) { app.offline.asking = v })
            }
            // Android only: another app (YTDLnis, Seal) takes the link through the system's share intents
            if (app.platform.name == "android") add(Triple(Sym.Download.outline, tr("Download with another app")) { app.download(v) })
            add(Triple(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") })
            for ((label, act) in extra) add(Triple(null, label, act))
            add(Triple(null, "-", {}))
            add(Triple(Sym.Close.outline, tr("Not interested")) { Learn.notInterested(app.store, v, channel = false); app.toast(tr("Got it. You'll see less like this")) })
            if (v.channelId.isNotEmpty()) add(Triple(Sym.Close.outline, tr("Don't recommend this channel")) {
                Learn.notInterested(app.store, v, channel = true); app.toast(tr("This channel won't be recommended"))
            })
        }
        if (LocalCompact.current) {
            // a phone: a sheet from the bottom with an icon on each row, as YouTube's app
            if (open) androidx.compose.material3.ModalBottomSheet(modifier = Modifier.sheetTop(), onDismissRequest = { open = false }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    for ((icon, label, act) in rows) {
                        if (label == "-") { androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp)); continue }
                        Row(Modifier.fillMaxWidth().clickable { open = false; act() }.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon ?: Sym.ChevronRight.outline, null, Modifier.size(24.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
                        }
                    }
                }
            }
        } else DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((_, label, act) in rows) {
                if (label == "-") continue
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() })
            }
        }
    }
    if (pick) PlaylistPicker(v, app) { pick = false }
}

/** Big card: thumbnail on top, avatar + title + details below (home, subscriptions, search). */
@Composable
fun VideoCard(v: Video, app: AppState, queue: List<Video> = emptyList()) {
    if (!app.kidsAllow(v.channelId)) return
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    // as the web app 1.0 draws them: a rounded picture (on a phone inside the page's 12dp margin,
    // one video per row), the avatar lined up with its edge, a bold title, the rows well apart
    val phone = LocalCompact.current
    // a phone: YouTube's app's full-bleed picture (no margin, square corners), the words 12dp in
    Column(Modifier.fillMaxWidth()
        .warmOnPress(app, v).tvClickable(if (phone) RoundedCornerShape(0.dp) else RoundedCornerShape(18.dp), onLongClick = { menu.value = true }) { app.watch(v, queue) }.padding(bottom = if (phone) 20.dp else 32.dp)
        .let { m -> if (app.store.settings.value.dimWatched && app.current == Screen.Subs && app.store.lib.state.value.hist.any { it.id == v.id }) m.alpha(0.45f) else m }) {
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = if (phone) 0 else 16)
        Row(Modifier.padding(top = 12.dp, start = if (phone) 12.dp else 0.dp, end = if (phone) 4.dp else 0.dp)) {
            if (v.channelId.isNotEmpty()) {
                Avatar(channelAvatar(app, v.channelId, v.avatar), 36,
                    Modifier.notOnTv().clickable { app.go(Screen.Channel(v.channelId, v.author)) })
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titleOf(v, app), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            VideoMenu(v, app, openState = menu)
        }
    }
}

/** Compact row: small thumbnail on the left (related videos, playlists, history). */
@Composable
fun VideoRow(v: Video, app: AppState, queue: List<Video> = emptyList(), extra: List<Pair<String, () -> Unit>> = emptyList(), thumbWidth: Dp = 168.dp, big: Boolean = false, onClick: (() -> Unit)? = null) {
    if (!app.kidsAllow(v.channelId)) return
    val calm = app.store.settings.value.calm
    val menu = remember { mutableStateOf(false) }
    // a narrow column (a small window, a split screen): the picture gives way so the title stays readable
    androidx.compose.foundation.layout.BoxWithConstraints { val tw = minOf(thumbWidth, maxWidth * 0.42f)
    Row(
        Modifier.fillMaxWidth().let { if (onClick == null) it.warmOnPress(app, v) else it }.tvClickable(RoundedCornerShape(16.dp), onLongClick = { menu.value = true }) { if (onClick != null) onClick() else app.watch(v, queue) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // big: YouTube's search results on a computer, a large picture and a larger title beside it
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.width(tw), rounded = if (big) 18 else 12)
        Column(Modifier.weight(1f).padding(start = if (big) 16.dp else 10.dp)) {
            val title = titleOf(v, app)
            if (big) Text(title, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (big && webDesk(app) && v.author.isNotEmpty()) {
                // the website's search result: views and age, then the channel with its picture
                Text(v.subline(calm, withAuthor = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    if (v.channelId.isNotEmpty()) Avatar(channelAvatar(app, v.channelId, v.avatar), 24)
                    Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp))
                }
            } else Text(v.details(app, calm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        VideoMenu(v, app, extra, openState = menu)
    }
    }
}

@Composable
fun ChannelRow(c: Channel, app: AppState) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp)) { app.go(Screen.Channel(c.id, c.name)) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the picture at the start (it sat alone in the middle of a thumbnail's width)
        Avatar(c.avatar, if (LocalCompact.current) 56 else 88)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.handle.isNotEmpty()) Text(c.handle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (c.subsText.isNotEmpty()) Text(c.subsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        SubscribeButton(c.id, c.name, c.avatar, app)
    }
}

@Composable
fun PlaylistRow(p: Playlist, app: AppState) {
    Row(
        Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(16.dp)) { app.go(Screen.Playlist(p.id, p.title)) }.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // with thumbnails off the box stays (it carries the video count) but is left plain
        Box(Modifier.width(if (app.store.settings.value.hideThumbs) 72.dp else 168.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            if (!app.store.settings.value.hideThumbs) Img(p.thumb, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.PlaylistPlay.outline, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(p.countText, color = Color.White, fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(p.author, tr("Playlist")).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SubscribeButton(cid: String, name: String, thumb: String, app: AppState) {
    // collected, so the button turns over the moment you press it (it used to wait for the page to reload)
    val subbed = cid in app.store.lib.state.collectAsState().value.subs
    // from the bright pill to the grey one (and back) in a short fade, not a jump
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (subbed) c.surfaceVariant else c.primary, tween(Motion.MEDIUM))
    val fg by animateColorAsState(if (subbed) c.onSurface else c.onPrimary, tween(Motion.MEDIUM))
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(if (subbed) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
            app.store.subscribe(cid, name, thumb, !subbed)
            if (!subbed) scope.launch { pop.snapTo(0.9f); pop.animateTo(1f, spring(0.4f, Spring.StiffnessMedium)) }
        },
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.height(40.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
    ) { Text(tr(if (subbed) "Subscribed" else "Subscribe"), fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun Busy(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
}

@Composable
fun Problem(msg: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) Button(onClick = retry, modifier = Modifier.padding(top = 12.dp)) { Text(tr("Try again")) }
    }
}

@Composable
fun Empty(msg: String) {
    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun idOf(x: Any?): Any? = (x as? Item)?.id ?: (x as? app.tubeless.engine.Comment)?.id ?: x

/** Loads a first page, then more pages as the end of the list comes into view. */
class Pager<T>(private val first: suspend () -> Page<T>, private val more: suspend (String) -> Page<T>) {
    val items = androidx.compose.runtime.mutableStateListOf<T>()
    var next by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var started = false

    suspend fun load() {
        if (loading) return
        loading = true; error = null
        try {
            val p = first()
            items.clear(); items.addAll(p.items); next = p.next
        } catch (e: Exception) {
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false; started = true }
    }

    suspend fun loadMore() {
        val t = next ?: return
        if (loading) return
        loading = true
        try {
            val p = more(t)
            val have = items.map(::idOf).toHashSet()
            items.addAll(p.items.filter { idOf(it) !in have })
            next = p.next.takeIf { it != t && p.items.isNotEmpty() }
        } catch (e: Exception) {
            next = null
        } finally { loading = false }
    }
}

@Composable
fun <T> rememberPager(key: Any?, first: suspend () -> Page<T>, more: suspend (String) -> Page<T>): Pager<T> {
    val p = remember(key) { Pager(first, more) }
    LaunchedEffect(key) { if (!p.started) p.load() }
    return p
}

/** A responsive list of items: one column on a phone, a grid of cards on a wide window. */
@Composable
fun ItemGrid(
    items: List<Item>,
    app: AppState,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    loading: Boolean = false,
    onEnd: (suspend () -> Unit)? = null,
    rows: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    /** with rows: one column of large rows down the middle, as YouTube lists search results on a computer */
    wideRows: Boolean = false,
    /** the cards look on Home: the first video large */
    hero: Boolean = false,
    /** pulled down (touch), or its tab tapped again at the top: a fresh list */
    refresh: (suspend () -> Unit)? = null,
    /** search: a run of Shorts (one of YouTube's Shorts shelves) is one compact shelf between the videos, as YouTube shows it */
    shortsShelves: Boolean = false,
) {
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    if (onEnd != null) {
        LaunchedEffect(state, items.size) {
            snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                if (last >= state.layoutInfo.totalItemsCount - 4) scope.launch { onEnd() }
            }
        }
    }
    val list = items.distinctBy { it.id }
    // TV: the first card takes the remote's focus when the list arrives, so the arrows work at once
    val tv = LocalPlatform.current.tv
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (tv) LaunchedEffect(list.isNotEmpty()) {
        if (list.isNotEmpty() && !focused) { delay(150); focused = runCatching { first.requestFocus() }.isSuccess }
    }
    val big = rows && wideRows
    // phones: the top card plays muted as YouTube's feeds do (Settings → Playback in feeds)
    if (LocalCompact.current && app.platform.touch && !tv && !rows && look != 2) {
        val byId = remember(list) { list.filterIsInstance<Video>().associateBy { it.id } }
        FeedAutoplay(app, state) { k -> byId[k] }
    }
    // the cards already shown: those arrive once, not again each time they scroll back into view
    val seen = remember { HashSet<Any>() }
    if (list.isEmpty() && loading && header == null) { Skeleton(rows, wideRows); return }
    Refreshable(app, refresh, atTop = { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }, toTop = {
        if (state.firstVisibleItemIndex > 8) state.scrollToItem(8)
        state.animateScrollToItem(0)
    }) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    // the large rows' picture shrinks with the window, so a narrow one still leaves room for the title
    val bigThumb = when { maxWidth > 900.dp -> 360.dp; maxWidth > 640.dp -> 246.dp; else -> 168.dp }
    // a computer: the website's grid of 3 to 6 columns and its cards
    val web = webDesk(app) && !rows && !hero
    val webQueue = remember(list) { list.filterIsInstance<Video>() }
    // YouTube's search answers carry its Shorts in shelves of 5 to 20; laid out one by one they took over the
    // results (35 Shorts to 14 videos for "how to cook pasta"), so each run of them is one small shelf here
    val runs = if (shortsShelves) shortsRuns(list) else emptyMap()
    val inShelf = remember(runs) { runs.values.flatMap { r -> r.drop(1).map { v -> v.id } }.toHashSet() }
    val cells = if (runs.isEmpty()) list else list.filter { it.id !in inShelf }
    LazyVerticalGrid(
        columns = if (web) GridCells.Fixed(webCols(maxWidth - 48.dp)) else if (big) GridCells.Fixed(1) else if (rows) GridCells.Adaptive(560.dp) else if (tv) GridCells.Fixed(4)
            else if (look == 2) (if (LocalCompact.current) GridCells.Fixed(2) else GridCells.Adaptive(250.dp))
            else if (look == 1 || look == 3) GridCells.Adaptive(330.dp) else GridCells.Adaptive(320.dp),
        state = state,
        modifier = Modifier.let { if (big) it.widthIn(max = 1120.dp) else it }.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (web) 16.dp else if (LocalCompact.current || look != 0) 0.dp else if (tv) 22.dp else 20.dp),
        contentPadding = PaddingValues(horizontal = if (LocalCompact.current) (if (look == 2 && !rows) 7.dp else 0.dp) else if (tv) TV_EDGE else 24.dp, vertical = if (tv) 16.dp else 8.dp),
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
        itemsIndexed(cells, key = { _, it -> it.id }, span = { i, it -> if (it.id in runs) GridItemSpan(maxLineSpan) else if ((it is Video || (web && it is Playlist)) && !rows && !(hero && i == 0)) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) { i, it ->
            val run = runs[it.id]
            if (run != null) { ShortsRun(run, app); return@itemsIndexed }
            Box((if (tv && it.id == list[0].id) Modifier.focusRequester(first) else Modifier).arrive(it.id, i, seen)) {
                when (it) {
                    is Video -> if (rows) VideoRow(it, app, thumbWidth = if (big) bigThumb else 168.dp, big = big)
                        // TV: the big-screen card, which grows in a white frame when chosen
                        else if (tv) TvCard(it, app, Modifier.fillMaxWidth().padding(bottom = 30.dp))
                        else if (web) WebCard(it, app, webQueue)
                        else if (hero && i == 0) HeroCard(it, app)
                        else if (look == 2) GlassCard(it, app)
                        else if (look == 1 || look == 3) GlassCard(it, app, solid = true)
                        else VideoCard(it, app)
                    is Channel -> ChannelRow(it, app)
                    is Playlist -> if (web) WebPlaylistCard(it, app) else PlaylistRow(it, app)
                }
            }
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Busy() }
    }
    }
    }
}

/** the runs of 2 or more Shorts in a row, by their first one's id */
private fun shortsRuns(list: List<Item>): Map<String, List<Video>> {
    val out = LinkedHashMap<String, List<Video>>()
    var i = 0
    while (i < list.size) {
        val a = list[i]
        if (a is Video && a.short) {
            var j = i
            while (j < list.size && (list[j] as? Video)?.short == true) j++
            if (j - i >= 2) out[a.id] = list.subList(i, j).map { it as Video }
            i = j
        } else i++
    }
    return out
}

/**
 * One Shorts shelf in search results, as on YouTube: a phone shows two (four when there are enough) standing
 * side by side with "Show more" for the rest; a wider screen a row to scroll.
 */
@Composable
private fun ShortsRun(run: List<Video>, app: AppState) {
    val phone = LocalCompact.current
    var more by remember(run.first().id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
            Icon(Sym.Bolt.fill, null, Modifier.size(22.dp), tint = Color(0xFFFF0033))
            Text("Shorts", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 6.dp))
        }
        @Composable
        fun Tile(v: Video, m: Modifier) {
            val shape = RoundedCornerShape(12.dp)
            Column(m) {
                Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(shape).clickable { app.watch(v) }) {
                    Img(v.thumb, Modifier.fillMaxSize())
                }
                Text(titleOf(v, app), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, end = 4.dp))
                if (v.viewsText.isNotEmpty()) Text(v.viewsText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (phone) {
            val shown = if (more) run else run.take(if (run.size >= 4) 4 else 2)
            for (pair in shown.chunked(2)) Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in pair) Tile(v, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            if (run.size > shown.size || more) androidx.compose.material3.OutlinedButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth()) {
                Text(tr(if (more) "Show less" else "Show more"))
            }
        } else androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(run.size) { k -> Tile(run[k], Modifier.width(200.dp)) }
        }
    }
}

/**
 * A list that can be made fresh, as YouTube's: pulled down from the top on a touch screen it reloads
 * (a spinner comes down with the finger); on a computer a two-finger swipe (or the wheel) on past the top
 * does the same. Its tab (or the logo) tapped again glides it back to the top and reloads it. A reload
 * shows a spinner at the top and lands at the top of the fresh list.
 */
@Composable
fun Refreshable(app: AppState, refresh: (suspend () -> Unit)?, atTop: () -> Boolean, toTop: suspend () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val again: () -> Unit = {
        if (refresh != null && !busy) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
            scope.launch {
                busy = true
                try {
                    if (!atTop()) runCatching { toTop() }
                    val t0 = Sys.nowMs()
                    refresh()
                    // a quick answer still shows the spinner long enough to be seen
                    (600 - (Sys.nowMs() - t0)).takeIf { it > 0 }?.let { delay(it) }
                } finally { busy = false }
                // the fresh list from its top
                runCatching { toTop() }
            }
        }
    }
    val tick = app.reselect
    val start = remember { tick }
    LaunchedEffect(tick) { if (tick != start) { if (refresh != null) again() else toTop() } }
    if (refresh != null && app.platform.touch && !app.platform.tv) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(busy, again, Modifier.fillMaxSize()) { content() }
    } else if (refresh != null && !app.platform.tv) DeskPull(busy, atTop, again, content)
    else content()
}

/**
 * A computer's pull to refresh: a scroll gesture that starts with the list at its top and keeps going up
 * (a trackpad's two-finger swipe down, or a few wheel notches up) pulls a spinner down; past the mark it
 * reloads. A gesture that only reaches the top on its way up never reloads (the next one must start there).
 */
@Composable
private fun DeskPull(busy: Boolean, atTop: () -> Boolean, pull: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableStateOf(0f) }      // 0..1 of the way to a reload
    var lastAt by remember { mutableStateOf(0L) }
    val doPull by androidx.compose.runtime.rememberUpdatedState(pull)
    val top by androidx.compose.runtime.rememberUpdatedState(atTop)
    // the gesture ended short of the mark: the spinner goes back up
    LaunchedEffect(lastAt) { if (drag > 0f) { delay(DESK_PULL_GAP + 40); drag = 0f } }
    val shown by animateFloatAsState(if (busy) 1f else drag, tween(if (busy || drag == 0f) Motion.SHORT else 60))
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        var acc = 0.0
        var armed = false
        var fired = false
        var last = 0L
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (e.type != androidx.compose.ui.input.pointer.PointerEventType.Scroll) continue
                val dy = e.changes.sumOf { it.scrollDelta.y.toDouble() }
                val now = e.changes.firstOrNull()?.uptimeMillis ?: 0L
                if (now - last > DESK_PULL_GAP) { acc = 0.0; fired = false; armed = top() }
                last = now
                if (!armed || fired) continue
                if (dy < 0) acc -= dy else if (dy > 0) { acc = 0.0; armed = false; drag = 0f; continue }
                drag = (acc / DESK_PULL_MARK).toFloat().coerceIn(0f, 1f)
                lastAt = now
                if (acc >= DESK_PULL_MARK) { fired = true; drag = 0f; doPull() }
            }
        }
    }) {
        content()
        if (shown > 0.01f) {
            val c = MaterialTheme.colorScheme
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 8.dp + 40.dp * shown).size(40.dp)
                    .graphicsLayer { alpha = shown.coerceIn(0f, 1f); shadowElevation = 6.dp.toPx(); shape = CircleShape; clip = true }
                    .background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else CircularProgressIndicator(progress = { drag }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
        }
    }
}

/** ms without scroll events: the gesture (and its inertia) is over */
private const val DESK_PULL_GAP = 220L
/** how far past the top a gesture goes to reload: about three wheel notches, a short firm trackpad swipe */
private const val DESK_PULL_MARK = 3.0

/**
 * What the web app shows while a page loads: soft grey shapes where the pictures and the lines of text
 * will be, a light sweeping across them, instead of a spinner. rows: the list form (a picture on the
 * left); wide: the one large column of search results on a computer.
 */
@Composable
fun Skeleton(rows: Boolean = false, wide: Boolean = false) {
    val phone = LocalCompact.current
    val sweep by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
    )
    val grey = MaterialTheme.colorScheme.surfaceContainer
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    fun Modifier.bone(shape: Shape = RoundedCornerShape(6.dp)) = this.clip(shape).background(grey).drawBehind {
        val w = size.width
        val x = -0.5f * w + sweep * 2f * w
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x - w * 0.3f, endX = x + w * 0.3f))
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val big = rows && wide
        val thumb = when { !big -> 168.dp; maxWidth > 900.dp -> 360.dp; maxWidth > 640.dp -> 246.dp; else -> 168.dp }
        LazyVerticalGrid(
            columns = if (big) GridCells.Fixed(1) else if (rows) GridCells.Adaptive(560.dp) else GridCells.Adaptive(320.dp),
            modifier = Modifier.let { if (big) it.widthIn(max = 1120.dp) else it }.fillMaxSize(),
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(if (phone) 0.dp else 20.dp),
            contentPadding = PaddingValues(horizontal = if (phone) 0.dp else 24.dp, vertical = 8.dp),
        ) {
            items(12) {
                if (rows) Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Box(Modifier.width(thumb).aspectRatio(16f / 9f).bone(RoundedCornerShape(if (big) 18.dp else 12.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                        Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.5f).height(12.dp).bone())
                    }
                } else Column(Modifier.padding(horizontal = if (phone) 12.dp else 0.dp).padding(bottom = if (phone) 22.dp else 32.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).bone(RoundedCornerShape(16.dp)))
                    Row(Modifier.padding(top = 12.dp, end = 12.dp)) {
                        Box(Modifier.size(36.dp).bone(CircleShape))
                        Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                            Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(12.dp).bone())
                        }
                    }
                }
            }
        }
    }
}

/** a grey stand-in with a light sweeping across it, for what is still loading (see [Skeleton]) */
@Composable
fun Modifier.bone(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    val sweep by rememberInfiniteTransition().animateFloat(
        0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
    )
    val grey = MaterialTheme.colorScheme.surfaceContainer
    val light = MaterialTheme.colorScheme.surfaceContainerHighest
    return this.clip(shape).background(grey).drawBehind {
        val w = size.width
        val x = -0.5f * w + sweep * 2f * w
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x - w * 0.3f, endX = x + w * 0.3f))
    }
}

/** a video still loading: a full-width card ([big], phones) or a row with a picture on the left */
@Composable
fun BoneVideo(big: Boolean, thumb: Dp = 168.dp) {
    if (big) Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).bone(RoundedCornerShape(0.dp)))
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
            Box(Modifier.size(36.dp).bone(CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
                Box(Modifier.fillMaxWidth(0.9f).height(16.dp).bone())
                Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(12.dp).bone())
            }
        }
    } else Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(Modifier.width(thumb).aspectRatio(16f / 9f).bone(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 2.dp)) {
            Box(Modifier.fillMaxWidth(0.9f).height(14.dp).bone())
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.5f).height(11.dp).bone())
        }
    }
}

/** a comment still loading */
@Composable
fun BoneComment() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Box(Modifier.size(32.dp).bone(CircleShape))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Box(Modifier.fillMaxWidth(0.35f).height(11.dp).bone())
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.95f).height(13.dp).bone())
            Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(13.dp).bone())
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

// ---------------------------------------------------------------- rounded, tonal pieces
// The quieter look of music apps such as Flow: soft grey boxes with big round corners instead of
// lines and outlines, pill-shaped buttons, icons in circles.

private val PillShape = RoundedCornerShape(50)

/** a pill-shaped button: grey, or [filled] with the text colour (the main action, such as Play all) */
@Composable
fun PillButton(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, filled: Boolean = false,
    enabled: Boolean = true, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val fg = if (filled) c.surface else c.onSurface
    Row(
        modifier.height(height).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(PillShape)
            .background(if (filled) c.onSurface else c.surfaceVariant)
            .then(if (enabled) Modifier.tvClickable(PillShape, sink = 0.94f, onClick = onClick) else Modifier)
            .padding(horizontal = if (label.isEmpty()) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = fg)
        if (icon != null && label.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (label.isNotEmpty()) Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** a round grey button with an icon; [on] tints it teal (shuffle or repeat switched on) */
@Composable
fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String?, size: Dp = 44.dp, on: Boolean = false,
    tint: Color = Color.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.size(size).tvClickable(CircleShape, sink = 0.9f, onClick = onClick).clip(CircleShape).background(if (on) accentSoft else c.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size * 0.5f), tint = if (on) accent else if (tint != Color.Unspecified) tint else c.onSurface)
    }
}

/** a small label in the accent colour above a group of rows ("Your content", "Playlists") */
@Composable
fun GroupLabel(text: String) = SectionLabel(text, Modifier.padding(start = 4.dp))

/**
 * rows as one segmented group: the group's outer corners large, each row its own
 * piece with small corners, 2 dp apart
 */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** a row of a [RowGroup]: the icon, a title, a line under it, a count, an arrow */
@Composable
fun GroupRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String = "", trailing: String = "",
    arrow: Boolean = true, onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.surfaceContainer)
            .tvClickable(RoundedCornerShape(6.dp), onClick = onClick).padding(horizontal = 18.dp, vertical = if (sub.isEmpty()) 18.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = accent)
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (arrow) Icon(Sym.ChevronRight.outline, null, Modifier.padding(start = 4.dp).size(22.dp), tint = c.onSurfaceVariant)
    }
}

/** a shelf's title, with its icon; when it opens a page, the whole line does, with an arrow at the end */
@Composable
fun ShelfTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onMore: (() -> Unit)? = null, end: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onMore != null) Modifier.tvClickable(RoundedCornerShape(12.dp), onClick = onMore) else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 10.dp).size(22.dp))
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (end != null) end()
        if (onMore != null) Icon(Sym.ChevronRight.outline, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** a search box as a grey pill, without the outline */
@Composable
fun SearchField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    leading: androidx.compose.ui.graphics.vector.ImageVector = Sym.Search.outline, onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.TextField(
        value, onChange, modifier.fillMaxWidth().heightIn(min = 52.dp), singleLine = true,
        shape = PillShape,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(leading, null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Sym.Close.outline, tr("Clear")) }
                if (trailing != null) trailing()
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (onSearch != null) androidx.compose.ui.text.input.ImeAction.Search else androidx.compose.ui.text.input.ImeAction.Done),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * The top of a list of videos (a playlist, Watch later, liked videos): its picture large and
 * rounded, the name, a line about it, Play all and Shuffle, and whatever else [more] adds.
 */
@Composable
fun ListHeader(
    cover: String, title: String, line: String, enabled: Boolean, play: () -> Unit, shuffle: () -> Unit,
    /** the panel down the left of a wide window (see [SplitList]) */
    panel: Boolean = false,
    more: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    if (panel) {
        // as YouTube's website: a tall card with the cover's colours, soft, behind the cover and the words
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            Backdrop(cover.ifEmpty { null }, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (cover.isNotEmpty()) Img(cover, Modifier.fillMaxSize())
                    else Icon(Sym.PlaylistPlay.outline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 20.dp))
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(tr("Play all"), Sym.PlayArrow.fill, filled = true, enabled = enabled, modifier = Modifier.weight(1f), onClick = play)
                    PillButton(tr("Shuffle"), Sym.Shuffle.fill, enabled = enabled, modifier = Modifier.weight(1f), onClick = shuffle)
                }
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { more() }
            }
        }
        return
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
        val wide = maxWidth > 700.dp
        val pic: @Composable (Modifier) -> Unit = { m ->
            Box(m.aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (cover.isNotEmpty()) Img(cover, Modifier.fillMaxSize())
                else Icon(Sym.PlaylistPlay.outline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val words: @Composable () -> Unit = {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton(tr("Play all"), Sym.PlayArrow.fill, filled = true, enabled = enabled, onClick = play)
                PillButton(tr("Shuffle"), Sym.Shuffle.fill, enabled = enabled, onClick = shuffle)
            }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { more() }
        }
        if (wide) Row(verticalAlignment = Alignment.CenterVertically) {
            pic(Modifier.width(360.dp))
            Column(Modifier.weight(1f).padding(start = 24.dp)) { words() }
        } else Column {
            pic(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            words()
        }
    }
}

/**
 * A playlist's page: on a wide window the cover, title and buttons stay in a panel on the left while
 * the videos scroll on the right, as on YouTube's website; narrower, the header scrolls away at the
 * top of the list. [list] gets whether it should put the header in itself.
 */
@Composable
fun SplitList(side: @Composable () -> Unit, list: @Composable (headerInList: Boolean) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        if (w > 1000.dp && !LocalPlatform.current.tv) Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(minOf(400.dp, w * 0.34f)).fillMaxHeight().padding(start = 16.dp, top = 8.dp, bottom = 16.dp)) { side() }
            Box(Modifier.weight(1f).fillMaxHeight()) { list(false) }
        } else list(true)
    }
}

/**
 * The web's cardIn: a card rises 16dp into place, fades in and grows to its size, the first dozen one
 * after another (30 ms apart), each card once (seen holds the ones that have arrived). Not on a TV.
 */
@Composable
fun Modifier.arrive(key: Any, order: Int, seen: MutableSet<Any>): Modifier {
    if (LocalPlatform.current.tv) return this
    val fresh = remember(key) { seen.add(key) }
    if (!fresh) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (order in 1..11) delay(order * 30L)
        a.animateTo(1f, tween(500, easing = Motion.out))
    }
    return graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 16.dp.toPx()
        val s = 0.985f + 0.015f * a.value
        scaleX = s; scaleY = s
    }
}

@Composable
fun Narrow(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 900.dp).fillMaxWidth()) { content() }
    }
}


// ---------------------------------------------------------------------- the looks (Settings → Design)

/** Design 2 (glass): a small see-through card, two a row on a phone */
@Composable
fun GlassCard(v: Video, app: AppState, queue: List<Video> = emptyList(), solid: Boolean = false) {
    // the opaque box around every video made the lists heavy: a plain card on a phone
    if (solid && LocalCompact.current) { VideoCard(v, app, queue); return }
    if (!app.kidsAllow(v.channelId)) return
    val menu = remember { mutableStateOf(false) }
    // solid: the big cards look, an opaque rounded box with the channel's picture; else see-through glass
    val shape = RoundedCornerShape(if (solid) 24.dp else 18.dp)
    val c = MaterialTheme.colorScheme
    val calm = app.store.settings.value.calm
    val darkBg = c.background.luminance() < 0.5f
    val edge = if (solid) Color.Transparent else if (darkBg) Color(0x1FFFFFFF) else Color(0xCCFFFFFF)
    val fill = if (solid) c.surfaceContainer else if (darkBg) Color(0x14FFFFFF) else Color(0x99FFFFFF)
    val phone = LocalCompact.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (solid && phone) 12.dp else 5.dp, vertical = if (solid) 7.dp else 5.dp)
            .clip(shape).background(fill).border(1.dp, edge, shape)
            .tvClickable(shape, onLongClick = { menu.value = true }) { app.watch(v, queue) }.padding(if (solid) 8.dp else 6.dp),
    ) {
        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = if (solid) 18 else 13)
        Row(Modifier.padding(top = if (solid) 10.dp else 7.dp, start = 2.dp, bottom = if (solid) 4.dp else 0.dp)) {
            if (solid && v.channelId.isNotEmpty()) {
                Avatar(channelAvatar(app, v.channelId, v.avatar), 34,
                    Modifier.notOnTv().clickable { app.go(Screen.Channel(v.channelId, v.author)) })
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(titleOf(v, app), fontSize = if (solid) 15.sp else 13.sp, fontWeight = if (solid) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = if (solid) 20.sp else 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // the channel, views and age, as on the classic card
                Text(v.details(app, calm), style = if (solid) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            VideoMenu(v, app, openState = menu)
        }
    }
}

/** Design 1 (cards): Home's first video, large, its title over the picture */
@Composable
fun HeroCard(v: Video, app: AppState, queue: List<Video> = emptyList()) {
    if (!app.kidsAllow(v.channelId)) return
    val menu = remember { mutableStateOf(false) }
    val phone = LocalCompact.current
    val calm = app.store.settings.value.calm
    val shape = RoundedCornerShape(26.dp)
    Box(
        // a phone: the picture as it is; a computer: a wide banner cut from it, so it doesn't fill the window
        Modifier.fillMaxWidth().padding(horizontal = if (phone) 12.dp else 5.dp).padding(bottom = 18.dp, top = 4.dp)
            .aspectRatio(if (phone) 16f / 9f else 2.6f)
            .clip(shape).tvClickable(shape, onLongClick = { menu.value = true }) { app.watch(v, queue) },
    ) {
        Img(if (phone || app.platform.weak) v.thumb else v.thumbHigh, Modifier.fillMaxSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD9000000))))
        val label = v.badge()
        if (label.isNotEmpty()) Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(if (v.live) Red else Color(0xB3000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = if (phone) 18.dp else 28.dp, end = 56.dp, bottom = if (phone) 16.dp else 24.dp)
            .widthIn(max = 720.dp)) {
            Text(titleOf(v, app), color = Color.White, fontSize = if (phone) 19.sp else 26.sp, fontWeight = FontWeight.ExtraBold,
                lineHeight = if (phone) 24.sp else 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.details(app, calm), color = Color(0xD9FFFFFF), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { VideoMenu(v, app, openState = menu) }
    }
}

/** Design 1 (cards): the videos you stopped in the middle of, in a row that scrolls sideways */
@Composable
fun ContinueRow(app: AppState, forYou: Boolean = true) {
    val lib by app.store.lib.state.collectAsState()
    val left = remember(lib.hist, lib.pos) {
        lib.hist.asSequence().filter { m ->
            val p = lib.pos[m.id] ?: return@filter false
            val t = p.getOrNull(0) ?: 0; val len = p.getOrNull(1) ?: 0
            len > 0 && t > 20 && t < len - 30
        }.map { it.video() }.filter { app.kidsAllow(it.channelId) }.take(12).toList()
    }
    if (left.isEmpty()) return
    val phone = LocalCompact.current
    Text(tr("Continue watching"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(start = if (phone) 16.dp else 4.dp, top = 4.dp, bottom = 8.dp))
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = if (phone) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 18.dp),
    ) {
        items(left.size, key = { left[it].id }) { i ->
            val v = left[i]
            Column(Modifier.width(220.dp).clip(RoundedCornerShape(18.dp)).tvClickable(RoundedCornerShape(18.dp)) { app.watch(v) }) {
                Thumb(v, app, Modifier.fillMaxWidth(), rounded = 18, feedPlay = false)
                // kept clear of the card's rounded bottom corners, which cut off the start of the last line
                Text(titleOf(v, app), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp, bottom = 10.dp))
            }
        }
    }
    if (forYou) Text(tr("For you"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(start = if (phone) 16.dp else 4.dp, bottom = 8.dp))
}

/**
 * Back closes this first while [enabled] (a panel, the lyrics...), then goes back as usual. Hooks
 * stack: the newest one gets Back first, and one that is off lets it through, so a panel left open
 * on a page that is no longer in front never swallows a press.
 */
@Composable
fun BackHook(app: AppState, enabled: Boolean, onBack: () -> Unit) {
    val on by androidx.compose.runtime.rememberUpdatedState(enabled)
    val act by androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.compose.runtime.DisposableEffect(app) {
        val h: () -> Boolean = { if (on) { act(); true } else false }
        app.backHooks.add(h)
        onDispose { app.backHooks.remove(h) }
    }
}

/**
 * One piece of a segmented group (Material 3 Expressive): rounded large at the group's
 * outer corners and small between pieces, 2 dp apart, on the raised surface colour.
 */
@Composable
fun Segment(first: Boolean, last: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val big = 24.dp
    val small = 6.dp
    val shape = RoundedCornerShape(if (first) big else small, if (first) big else small, if (last) big else small, if (last) big else small)
    Box(modifier.fillMaxWidth().padding(top = if (first) 0.dp else 2.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
}

/** a section's name over its group: small, bold, in the accent colour */
@Composable
fun SectionLabel(t: String, modifier: Modifier = Modifier) {
    Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = accent,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
}

/**
 * Starts fetching a video's streams the moment a finger rests on its card,
 * so the player has them by the time the tap ends. A touch that turns into a scroll asks nothing:
 * the finger has to stay put for a moment first. Not on a metered connection.
 */
@Composable
fun Modifier.warmOnPress(app: AppState, v: Video): Modifier {
    if (v.live || v.short || v.id.isEmpty()) return this
    return pointerInput(v.id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val still = withTimeoutOrNull(90L) {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: true
            if (still && !app.platform.metered()) { Streams.prefetch(app.scope, v.id); Infos.prefetch(app.scope, v.id) }
        }
    }
}

/**
 * A sheet from the bottom stops under the status bar: opened all the way (the player's settings, a long
 * description) its grip and first row were under the clock and the battery (iOS).
 */
@Composable
fun Modifier.sheetTop(): Modifier = this.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
