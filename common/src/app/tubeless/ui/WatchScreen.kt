package app.tubeless.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import app.tubeless.engine.Dislikes
import app.tubeless.engine.Details
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.Channel
import app.tubeless.engine.Comment
import app.tubeless.engine.Playlist
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import app.tubeless.engine.YouTubeAccount
import app.tubeless.rec.Learn
import kotlinx.coroutines.launch

@Composable
fun WatchScreen(app: AppState) {
    val pb = app.playback
    val v = pb.video
    if (v == null) {
        Empty(tr("Nothing is playing"))
        return
    }
    val set = app.store.settings.value
    val token = pb.details?.commentsToken?.takeIf { !set.hideComments }
    val hasRelated = !set.hideRelated || pb.queue.isNotEmpty()
    // the comments open beside (wide) or under (phone) the video, which keeps playing
    var side by remember(v.id) { mutableStateOf(if (hasRelated && set.sideTab != "comments") "next" else "comments") }
    var commentsOpen by remember(v.id) { mutableStateOf(false) }
    // the transcript: a tab beside the video (wide) or a panel over the space under it (phone)
    var transcriptOpen by remember(v.id) { mutableStateOf(false) }
    val hasCaptions = !pb.stream?.captions.isNullOrEmpty()
    // signed in: the account's like, subscription and bell for this video
    AccountWatchLoader(app, v)
    // TV: the video plays on this page, beside what's next; OK on the video fills the screen
    // and Back comes back here (it used to fill the screen by itself, which got in the way)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // the 2.0 design: the video's colours, soft, behind the top of the page
        if (LocalLook.current == 3 && !app.platform.tv) Backdrop(v.thumb, Modifier.fillMaxWidth().height(560.dp))
        val tablet = app.platform.touch
        // a tablet held upright stacks like a phone (the video across the top, everything under it);
        // side by side would leave the video small in the middle of a tall screen
        val wide = maxWidth > 840.dp && !(tablet && maxHeight > maxWidth)
        val narrow = maxWidth < 600.dp
        // tablets and TVs: a narrower panel, so the video gets most of the width
        val sideWidth = if (tablet || app.platform.tv) minOf(380.dp, maxWidth * 0.32f) else minOf(420.dp, maxWidth * 0.4f)
        LaunchedEffect(pb.transcriptAsked) {
            if (!pb.transcriptAsked) return@LaunchedEffect
            pb.transcriptAsked = false
            if (wide) { side = "transcript"; transcriptOpen = true } else { commentsOpen = false; transcriptOpen = true }
        }
        if (wide && webDesk(app)) {
            WebWatchBody(app, v, token, hasRelated, hasCaptions, transcriptOpen, { transcriptOpen = it }, maxWidth, maxHeight)
        } else if (wide) {
            // tablets and the desktop: the video and its details on the left; up next or the comments on the right
            // the iPad lays it out like YouTube on a computer: the details and the comments scroll under
            // the video, and the panel beside it has only what's next
            // every tablet (iPad and Android alike) lays it out as YouTube's tablet app: the details and the
            // comments scroll under the video, the panel beside it has what's next
            val pc = tablet
            Row(Modifier.fillMaxSize()) {
                if (tablet && !pc) {
                    // tablets: the video as large as the screen allows, with only its title under it;
                    // the description, buttons and channel move into the panel (Details)
                    Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.Center) {
                        PlayerBox(app, sideGestures = true)
                        if (set.underVideo != 2) Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                } else {
                    var pcSorted by remember(token) { mutableStateOf(token) }
                    val pager = if (pc && token != null) (pcSorted ?: token).let { t -> rememberPager(t, { YouTube.comments(t) }, { YouTube.comments(it) }) } else null
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(if (pc) 12.dp else 16.dp)) {
                        item { Box(Modifier.clip(RoundedCornerShape(20.dp))) { PlayerBox(app, sideGestures = pc) } }
                        under(app, v, flush = true)
                        if (pager != null) {
                            item { SectionTitle(tr("Comments")) }
                            comments(app, pager, commentSorts(token!!, app.playback.details), token, pcSorted ?: token) { pcSorted = it }
                        }
                    }
                }
                val tabs = buildList {
                    if (hasRelated) add("next" to tr("Up next"))
                    if (token != null && !pc) add("comments" to tr("Comments"))
                    if (tablet && !pc && set.underVideo != 2) add("details" to tr("Details"))
                    // the transcript as a tab next to the others; alone (up next and comments hidden) only
                    // when asked for, or it would fill the side of every video with no way to close it
                    if (hasCaptions && (isNotEmpty() || transcriptOpen)) add("transcript" to tr("Transcript"))
                }
                if (tabs.isNotEmpty()) {
                    val shown = side.takeIf { s -> tabs.any { it.first == s } } ?: tabs.first().first
                    Column(Modifier.width(sideWidth).padding(top = 8.dp, end = 8.dp).riseIn(v.id)) {
                        if (tabs.size > 1) {
                            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((k, label) in tabs) Chip(shown == k, onClick = { side = k }, label = label)
                            }
                        }
                        when {
                            shown == "comments" && token != null -> Comments(app, token)
                            shown == "details" -> LazyColumn(Modifier.fillMaxSize()) { item { Info(app, v) } }
                            shown == "transcript" -> TranscriptPanel(app, if (tabs.size == 1) ({ transcriptOpen = false }) else null)
                            else -> LazyColumn(Modifier.fillMaxSize()) { related(app, big = false, thumb = if (sideWidth < 340.dp) 128.dp else 200.dp) }
                        }
                    }
                }
            }
        } else {
            // phones: pull the video down and the whole page follows the finger, shrinking toward the
            // corner as it goes, as YouTube's page folds into its mini player; let go past a quarter
            // of the screen (or with a flick) and it drops into the mini player, otherwise it springs back
            val scope = rememberCoroutineScope()
            val pull = remember(v.id) { Animatable(0f) }
            val tall = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            Column(Modifier.fillMaxSize().graphicsLayer {
                val f = (pull.value / tall).coerceIn(0f, 1f)
                translationY = pull.value * 0.85f
                val k = 1f - 0.45f * f
                scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.95f, 0f)
                alpha = 1f - 0.7f * f
            }) {
                PlayerBox(app,
                    onPull = { dy -> scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) } },
                    onPullEnd = { speed ->
                        scope.launch {
                            if (pull.value > tall * 0.25f || (speed > 1200f && pull.value > 24f)) {
                                pull.animateTo(tall, tween(Motion.SHORT + 40, easing = Motion.emphasizedOut))
                                app.back()
                            } else pull.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f))
                        }
                    })
                Box(Modifier.fillMaxSize().riseIn(v.id)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        under(app, v)
                        if (token != null) item { CommentsTeaser(app, token) { commentsOpen = true } }
                        // phones: full-width cards; an upright tablet: rows with a larger picture
                        if (narrow) related(app, big = true) else related(app, big = false, thumb = 240.dp)
                    }
                    // the comments slide up over the space under the video, as on YouTube, and back
                    // down when closed; the video keeps playing above
                    androidx.compose.animation.AnimatedVisibility(
                        commentsOpen && token != null,
                        enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                            androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
                        exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                            androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            if (token != null) CommentsPanel(app, token) { commentsOpen = false }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        transcriptOpen && hasCaptions,
                        enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
                            androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
                        exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
                            androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
                    ) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            TranscriptPanel(app) { transcriptOpen = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBox(
    app: AppState, sideGestures: Boolean = false, onPull: ((Float) -> Unit)? = null, onPullEnd: ((Float) -> Unit)? = null,
    // in the phone's watch sheet: the sheet sizes the box (it shrinks into the mini player) and moves it
    sheet: Modifier? = null, controls: Boolean = true,
) {
    val pb = app.playback
    val st by app.player.state.collectAsState()
    val touch = app.platform.touch
    // Ambient mode: in the dark theme the video's colours glow around it, a fresh frame each second
    val showing = pb.error == null && !pb.loading && st.videoId == pb.video?.id
    val ambient = app.store.settings.state.collectAsState().value.ambient && MaterialTheme.colorScheme.background.luminance() < 0.3f
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(ambient && showing, st.videoId) {
        glow = null; AmbientFrame.now = null
        if (!ambient || !showing) return@LaunchedEffect
        while (true) {
            app.player.frame()?.let { glow = it; AmbientFrame.now = it }
            // paused: the picture only changes when you seek
            delay(if (app.player.state.value.playing) 1000 else 2500)
        }
    }
    Box(
        // (the phone's watch sheet draws the glow under the page itself: from here it lay over the title)
        (sheet ?: Modifier.fillMaxWidth().aspectRatio(16f / 9f)).ambientGlow(if (sheet == null) glow else null).background(Color.Black)
            // touch screens: swipe the video down to keep it playing in the mini player and go back,
            // up to watch it full screen (#64)
            .pointerInput(touch, sheet != null) {
                if (!touch || sheet != null) return@pointerInput
                var pulled = 0f
                // how fast the finger was moving when it let go (pixels a second), for a flick
                var speed = 0f
                var last = 0L
                detectVerticalDragGestures(
                    onDragStart = { pulled = 0f; speed = 0f; last = 0L },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        val t = change.uptimeMillis
                        if (last != 0L && t > last) speed = 0.6f * speed + 0.4f * (dy * 1000f / (t - last))
                        last = t
                        pulled += dy
                        // the page follows the finger down (not up: up is full screen)
                        if (onPull != null && (pulled > 0f || dy < 0f)) onPull(if (pulled > 0f) dy else 0f)
                    },
                    onDragCancel = { onPullEnd?.invoke(0f) },
                    onDragEnd = {
                        when {
                            pulled < -60.dp.toPx() -> { onPull?.invoke(-10_000f); app.setFull(true) }
                            onPullEnd != null -> onPullEnd(speed)
                            pulled > 80.dp.toPx() -> app.back()
                        }
                    },
                )
            },
    ) {
        val v = pb.video
        when {
            pb.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(pb.error!!, color = Color.White, textAlign = TextAlign.Center)
                if (pb.errorAge) Button(onClick = { app.platform.openExternal("https://www.youtube.com/watch?v=" + (v?.id ?: "")) },
                    modifier = Modifier.padding(top = 12.dp)) { Text(tr("Open on YouTube")) }
                else Button(onClick = { pb.retry() }, modifier = Modifier.padding(top = 12.dp)) { Text(tr("Try again")) }
            }
            pb.loading || st.videoId != v?.id -> {
                if (v != null && !app.store.settings.value.hideThumbs) Img(v.thumbHigh, Modifier.fillMaxSize())
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            else -> VideoArea(app, Modifier.fillMaxSize(), sideGestures = sideGestures, controls = controls)
        }
        // while loading there are no controls yet: keep a way back
        if (controls && (pb.error != null || pb.loading || st.videoId != v?.id)) {
            IconButton(onClick = { app.back() }, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Sym.ArrowBack.fill, tr("Back"), tint = Color.White)
            }
        }
    }
    if (sheet == null) PlayerNotes(app)
}

/** what the player has to say under the video: an error, or no sound */
@Composable
private fun PlayerNotes(app: AppState) {
    val st by app.player.state.collectAsState()
    st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
    // no sound (no speaker, a device that won't open, muted in the mixer): a calm note at the bottom of the
    // window, once per kind of problem, not red text by the video
    val issue = st.soundIssue
    LaunchedEffect(issue) {
        if (issue == null || !SoundNote.told.add(issue)) return@LaunchedEffect
        val devices = runCatching { app.player.audioDevices() }.getOrDefault(emptyList())
        val noDevice = devices.isEmpty() && "wouldn't open" in issue
        val r = app.snackbar.showSnackbar(
            tr(if (noDevice) "No speaker or headphones found. The video plays without sound." else issue),
            actionLabel = if (!noDevice && "Sound output" in issue && devices.isNotEmpty()) tr("Sound output") else null,
            withDismissAction = true, duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) app.go(Screen.SettingsPage("player"))
    }
}

/** the sound problems already told about in this run (each once, not again with every video) */
private object SoundNote { val told = HashSet<String>() }

/** the last frame Ambient mode took from the video, for the phone's watch sheet to draw under its page */
private object AmbientFrame { var now by mutableStateOf<ImageBitmap?>(null) }

/**
 * Draws [frame] (a small blurred picture of the video) stretched past the edges of what follows,
 * fading out a little way above and to the sides and further below; each new frame fades in over
 * the last one.
 */
@Composable
private fun Modifier.ambientGlow(frame: ImageBitmap?): Modifier {
    var last by remember { mutableStateOf<ImageBitmap?>(null) }
    var now by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(frame) {
        if (frame === now) return@LaunchedEffect
        last = now
        now = frame
        fade.snapTo(0f)
        fade.animateTo(1f, tween(900))
    }
    if (last == null && now == null) return this
    return drawBehind {
        val w = size.width
        val h = size.height
        val area = Rect(-w * 0.12f, -h * 0.3f, w * 1.12f, h * 1.8f)
        val p = fade.value
        drawIntoCanvas { c ->
            c.saveLayer(area, Paint())
            fun img(b: ImageBitmap?, alpha: Float) {
                if (b == null || alpha <= 0f) return
                drawImage(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(area.left.toInt(), area.top.toInt()),
                    IntSize(area.width.toInt(), area.height.toInt()), alpha = alpha * 0.6f, filterQuality = FilterQuality.Low)
            }
            img(last, 1f - p)
            img(now, p)
            // fade toward the edges: down, then across
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, (-area.top / area.height) to Color.Black,
                    ((h - area.top) / area.height) to Color.Black, 1f to Color.Transparent,
                    startY = area.top, endY = area.bottom,
                ),
                area.topLeft, area.size, blendMode = BlendMode.DstIn,
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, (-area.left / area.width) to Color.Black,
                    ((w - area.left) / area.width) to Color.Black, 1f to Color.Transparent,
                    startX = area.left, endX = area.right,
                ),
                area.topLeft, area.size, blendMode = BlendMode.DstIn,
            )
            c.restore()
        }
    }
}

@Composable
private fun Info(app: AppState, v: Video, flush: Boolean = false) {
    val pb = app.playback
    val d = pb.details
    val lib by app.store.lib.state.collectAsState()
    val calm = app.store.settings.value.calm
    val hidden = app.store.settings.state.collectAsState().value.hideUnder
    fun has(part: String) = part !in hidden
    var sheet by remember(v.id) { mutableStateOf(false) }
    var open by remember(v.id) { mutableStateOf(false) }
    val viewsText = d?.viewsText?.takeIf { it.isNotEmpty() } ?: v.views.takeIf { it > 0 }?.let { tr("{n} views").replace("{n}", compact(it)) } ?: ""
    // a phone shortens the count as YouTube's app does: "3.8M views"
    val phoneViews = if (!LocalCompact.current) null else (v.views.takeIf { it > 0 } ?: viewsText.filter(Char::isDigit).toLongOrNull())
        ?.let { tr("{n} views").replace("{n}", compact(it)) }
    val line = listOfNotNull(
        (phoneViews ?: viewsText).takeIf { !calm && has("views") },
        d?.publishedText?.takeIf { has("date") },
    ).filter { it.isNotEmpty() }.joinToString("  ")
    val desc = pb.stream?.description?.takeIf { has("description") } ?: ""
    // a computer (not a phone or a tablet) lays it out as YouTube's website does: a larger title, a
    // larger channel line, and the views, date and the start of the description in a grey box below
    // flush: beside the up-next panel, where the page's margin already lines it up with the video
    val desk = !LocalCompact.current && !app.platform.touch
    Column(Modifier.padding(horizontal = if (flush) 0.dp else 12.dp, vertical = 10.dp)) {
        if (desk) {
            if (has("title")) SelectionContainer {
                Text(titleOf(v, app), fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        } else {
            // the title and one quiet line under it; either opens the description
            val openPanel = LocalOpenPanel.current
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { if (openPanel != null) openPanel("description") else sheet = true }) {
                if (has("title")) Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (desc.isNotEmpty())
                        Text(tr("...more"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        // the channel and its subscribe button side by side, then the buttons
        val showSub = v.channelId.isNotEmpty() && has("subscribe")
        val web = webDesk(app)
        // the website: the channel on the left and the buttons on the right of one row, when it is wide enough
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val oneRow = web && maxWidth > 820.dp
        val tight = maxWidth < 1040.dp
        Column {
        if (has("channel") || showSub) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (has("channel")) {
                val open = { if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) }
                Avatar(d?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null } ?: lib.subs[v.channelId]?.thumb ?: "", if (desk) 40 else 36, Modifier.clickable { open() })
                Column((if (oneRow) Modifier.widthIn(max = 260.dp) else Modifier.weight(1f, fill = !desk)).padding(start = if (desk) 12.dp else 10.dp).clickable { open() }) {
                    Text(v.author, style = if (desk) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!calm && has("subscribers") && !d?.subsText.isNullOrEmpty())
                        Text(d!!.subsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.width(if (desk) 24.dp else 12.dp))
            }
            if (showSub) SubscribeButton(v.channelId, v.author, d?.avatar?.ifEmpty { null } ?: v.avatar, app)
            if (showSub) ChannelBellButton(app, v.channelId)
            if (oneRow) { Spacer(Modifier.weight(1f)); Actions(app, v, ::has, web = true, top = 0.dp, iconOnly = tight) }
        }
        if (!oneRow) Actions(app, v, ::has, web = web)
        }
        }
        // a computer opens the description in place, as YouTube's website does (a sheet from the bottom
        // in the middle of a wide window looked like a phone's); "Show less" folds it back
        if (desk && (line.isNotEmpty() || desc.isNotEmpty())) Column(
            Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .let { if (open) it else it.clickable { open = true } }
                .animateContentSize(tween(Motion.MEDIUM, easing = Motion.standard))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            if (desc.isNotEmpty()) {
                if (!open) {
                    Text(desc, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(tr("...more"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                } else {
                    SelectionContainer {
                        LinkedText(desc, app, Modifier.fillMaxWidth().padding(top = 2.dp), onTime = { s -> app.player.seekTo(s * 1000L) })
                    }
                    ChapterCards(app, Modifier.padding(top = 20.dp)) {}
                    Text(tr("Show less"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp).clip(RoundedCornerShape(6.dp)).clickable { open = false }.padding(vertical = 2.dp))
                }
            }
        }
    }
    if (sheet) DescriptionSheet(app, v, viewsText) { sheet = false }
}

/** The description in a sheet from the bottom (tablets; a phone opens it as a panel under the video). */
@Composable
private fun DescriptionSheet(app: AppState, v: Video, viewsText: String, onClose: () -> Unit) {
    // open all the way at once, as the player's settings sheet does
    ModalBottomSheet(modifier = Modifier.sheetTop(), onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        DescriptionBody(app, v, viewsText, onClose)
    }
}

/**
 * The description as YouTube's app shows it: the title, likes, views and the date as big numbers,
 * the full text (links open, #tags search, a time jumps there), the chapters, and the channel.
 */
@Composable
internal fun DescriptionBody(app: AppState, v: Video, viewsText: String = "", onClose: () -> Unit) {
    val d = app.playback.details
    val calm = app.store.settings.value.calm
    val desc = app.playback.stream?.description ?: ""
    val views = viewsText.ifEmpty { d?.viewsText ?: "" }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp)) {
        item {
            // the title can be copied too, as the description under it (#72)
            SelectableInSheet(app) { Text(titleOf(v, app), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            // likes, views and the date side by side, the numbers large, as YouTube's
            val stats = listOfNotNull(
                d?.likesText?.takeIf { it.isNotEmpty() && !calm }?.let { it to tr("Likes") },
                (if (v.views > 0) compact(v.views) else views.substringBefore(' ')).takeIf { it.isNotEmpty() && !calm }?.let { it to tr("Views") },
                d?.publishedText?.takeIf { it.isNotEmpty() }?.let { it to tr("Uploaded") },
            )
            if (stats.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((big, small) in stats) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            // links open, #tags search, and a time like 3:25 jumps there in the video
            if (desc.isNotEmpty()) SelectableInSheet(app) {
                LinkedText(desc, app,
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    onTime = { s -> app.player.seekTo(s * 1000L); onClose() })
            }
        }
        // the chapters as cards in a row, as under YouTube's description; a tap jumps there
        if (app.playback.chapters.isNotEmpty()) item { ChapterCards(app, Modifier.padding(bottom = 24.dp), onClose) }
        // the channel at the end, as YouTube's: its picture, name and subscribers, Videos and About
        if (v.author.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { onClose(); if (v.channelId.isNotEmpty()) app.go(Screen.Channel(v.channelId, v.author)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(d?.avatar?.ifEmpty { null } ?: v.avatar.ifEmpty { null } ?: app.store.lib.value.subs[v.channelId]?.thumb ?: "", 40)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(v.author, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!calm && !d?.subsText.isNullOrEmpty()) Text(d!!.subsText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Sym.ChevronRight.outline, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The chapters as cards in a row, as under YouTube's description; a click jumps there. */
@Composable
private fun ChapterCards(app: AppState, modifier: Modifier, after: () -> Unit) {
    val chapters = app.playback.chapters
    if (chapters.isEmpty()) return
    Column(modifier) {
        Text(tr("Chapters"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapters) { c ->
                Column(Modifier.width(160.dp).clip(RoundedCornerShape(8.dp)).clickable { app.player.seekTo(c.startMs); after() }) {
                    if (c.thumb.isNotEmpty()) Img(c.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                    Text(c.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(durationMs(c.startMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * The buttons under the video: like (with its count) and dislike in one pill, then watch later,
 * save, share, download and next, whichever Settings → Under the video keeps.
 */
@Composable
private fun Actions(app: AppState, v: Video, has: (String) -> Boolean, web: Boolean = false, top: Dp = 12.dp, iconOnly: Boolean = false) {
    val pb = app.playback
    val lib by app.store.lib.state.collectAsState()
    val liked = lib.likes.any { it.id == v.id }
    val disliked = v.id in lib.dislikes
    val saved = lib.wl.any { it.id == v.id }
    var pick by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    // the dislike count YouTube hides: Return YouTube Dislike's estimate
    var votes by remember(v.id) { mutableStateOf<Dislikes.Votes?>(null) }
    val showVotes = app.store.settings.value.showDislikes && !calm
    if (showVotes) LaunchedEffect(v.id) { votes = Dislikes.votes(v.id) }
    // the thumb pops when you press it, as on YouTube: squeezed, then springing past its size and back
    val scope = rememberCoroutineScope()
    val upPop = remember { Animatable(1f) }
    val downPop = remember { Animatable(1f) }
    fun pop(a: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) = scope.launch {
        a.animateTo(0.7f, tween(80))
        a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    if (web) {
        // the website's row: like | dislike, Share, Save, Download, and ⋯ with the rest (nothing cut off)
        Row(Modifier.padding(top = top), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (has("likes")) LikePill(app, v, liked, disliked, votes, showVotes, calm, upPop, downPop) { a -> pop(a) }
            if (has("share")) Chip(Sym.Share.outline, if (iconOnly) "" else tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") }
            if (has("save")) Chip(Sym.LibraryAdd.outline, if (iconOnly) "" else tr("Save")) { pick = true }
            if (has("download") && !v.live && app.platform.canDownload) DownloadChip(app, v, iconOnly)
            var more by remember { mutableStateOf(false) }
            var send by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { more = true }, contentAlignment = Alignment.Center) {
                    Icon(Sym.MoreVert.fill, tr("More"), Modifier.size(20.dp).graphicsLayer { rotationZ = 90f })
                }
                androidx.compose.material3.DropdownMenu(more, onDismissRequest = { more = false }) {
                    if (has("later")) androidx.compose.material3.DropdownMenuItem(text = { Text(tr(if (saved) "Remove from Watch later" else "Save to Watch later")) },
                        leadingIcon = { Icon(if (saved) Sym.WatchLater.fill else Sym.WatchLater.outline, null) }, onClick = { more = false; app.store.setInList("wl", v, !saved) })
                    if (!pb.stream?.captions.isNullOrEmpty()) androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Show transcript")) },
                        leadingIcon = { Icon(Sym.Transcript.outline, null) }, onClick = { more = false; pb.transcriptAsked = true })
                    if (app.store.settings.value.nearby && app.near.available) androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Other device")) },
                        leadingIcon = { Icon(Sym.Cast.outline, null) }, onClick = { more = false; send = true })
                    if (pb.next != null && has("next")) androidx.compose.material3.DropdownMenuItem(text = { Text(tr("Next")) },
                        leadingIcon = { Icon(Sym.SkipNext.outline, null) }, onClick = { more = false; pb.skipNext() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(tr(if (WebWatch.theater) "Default view" else "Theater mode")) },
                        leadingIcon = { Icon(WebIcons.Theater, null) }, onClick = { more = false; WebWatch.theater = !WebWatch.theater })
                }
                if (send) SendToDialog(app, v) { send = false }
            }
        }
        if (pick) PlaylistPicker(v, app) { pick = false }
        return
    }
    // a phone: the row runs to the screen's edges as it scrolls (cut at the page's margin, "Download" ended mid-word)
    val bleed = if (LocalCompact.current) 12.dp else 0.dp
    LazyRow(
        Modifier.padding(top = 12.dp).layout { m, c ->
            val extra = (bleed * 2).roundToPx()
            val p = m.measure(if (c.hasBoundedWidth) c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra) else c)
            layout(if (c.hasBoundedWidth) c.maxWidth else p.width, p.height) { p.place(-extra / 2, 0) }
        },
        contentPadding = PaddingValues(horizontal = bleed), horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (has("likes")) item {
          Box(Modifier.clip(RoundedCornerShape(20.dp))) {
            Row(Modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                    app.store.setInList("likes", v, !liked)
                    if (!liked) { Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0); pop(upPop) }
                }.padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), Modifier.size(20.dp).graphicsLayer { scaleX = upPop.value; scaleY = upPop.value; rotationZ = (1f - upPop.value) * -40f })
                    // YouTube's count; where the channel hides it, the estimate
                    val n = pb.details?.likesText?.takeIf { !calm && it.any(Char::isDigit) }
                        ?: votes?.likes?.takeIf { showVotes && it > 0 }?.let(::compact) ?: pb.details?.likesText?.takeIf { !calm } ?: ""
                    if (n.isNotEmpty()) Text(n, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
                Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
                Box(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                    app.store.setInList("dislikes", v, !disliked)
                    if (!disliked) { Learn.nudgeChannel(app.store, v.channelId, v.author, -1.5); pop(downPop) }
                }.padding(start = 10.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike"), Modifier.size(20.dp).graphicsLayer { scaleX = downPop.value; scaleY = downPop.value })
                        val d = votes?.dislikes?.takeIf { showVotes }
                        if (d != null) Text(compact(d), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            // how many of the votes are likes: a thin line along the bottom of the pill, as YouTube once had
            val vt = votes?.takeIf { showVotes && it.likes + it.dislikes > 0 }
            if (vt != null) Box(Modifier.matchParentSize().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), contentAlignment = Alignment.BottomStart) {
                Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(vt.likes.toFloat() / (vt.likes + vt.dislikes)).height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)))
                }
            }
          }
        }
        // YouTube's app's order: like | dislike, Share, Download, Save (Watch later is in Save's list), then ours
        if (has("share")) item { Chip(Sym.Share.outline, tr("Share")) { app.share(v.title, "https://youtu.be/${v.id}") } }
        if (has("download") && !v.live && app.platform.canDownload) item { DownloadChip(app, v) }
        if (has("save")) item { Chip(if (saved) Sym.LibraryAdd.fill else Sym.LibraryAdd.outline, tr("Save")) { pick = true } }
        else if (has("later")) item { Chip(if (saved) Sym.WatchLater.fill else Sym.WatchLater.outline, tr("Watch later")) { app.store.setInList("wl", v, !saved) } }
        // nearby devices: carry on with this video on the TV or the computer
        if (app.store.settings.value.nearby && app.near.available) item {
            var open by remember { mutableStateOf(false) }
            Chip(Sym.Cast.outline, tr("Other device")) { open = true }
            if (open) SendToDialog(app, v) { open = false }
        }
        if (!pb.stream?.captions.isNullOrEmpty() && !app.platform.tv) item { Chip(Sym.Transcript.outline, tr("Transcript")) { pb.transcriptAsked = true } }
        if (pb.next != null && has("next")) item { Chip(Sym.SkipNext.outline, tr("Next")) { pb.skipNext() } }
    }
    if (pick) PlaylistPicker(v, app) { pick = false }
}

/** like | dislike in one pill, with Return YouTube Dislike's counts and the share of likes along its bottom */
@Composable
private fun LikePill(
    app: AppState, v: Video, liked: Boolean, disliked: Boolean, votes: Dislikes.Votes?, showVotes: Boolean, calm: Boolean,
    upPop: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, downPop: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    pop: (Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) -> Unit,
) {
    val pb = app.playback
    Box(Modifier.clip(RoundedCornerShape(20.dp))) {
        Row(Modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                app.store.setInList("likes", v, !liked)
                if (!liked) { Learn.nudgeChannel(app.store, v.channelId, v.author, 1.0); pop(upPop) }
            }.padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), Modifier.size(20.dp).graphicsLayer { scaleX = upPop.value; scaleY = upPop.value; rotationZ = (1f - upPop.value) * -40f })
                val n = pb.details?.likesText?.takeIf { !calm && it.any(Char::isDigit) }
                    ?: votes?.likes?.takeIf { showVotes && it > 0 }?.let(::compact) ?: pb.details?.likesText?.takeIf { !calm } ?: ""
                if (n.isNotEmpty()) Text(n, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
            }
            Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
            Box(Modifier.fillMaxHeight().tvClickable(RoundedCornerShape(20.dp), sink = 0.94f) {
                app.store.setInList("dislikes", v, !disliked)
                if (!disliked) { Learn.nudgeChannel(app.store, v.channelId, v.author, -1.5); pop(downPop) }
            }.padding(start = 10.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (disliked) Sym.ThumbDown.fill else Sym.ThumbDown.outline, tr("Dislike"), Modifier.size(20.dp).graphicsLayer { scaleX = downPop.value; scaleY = downPop.value })
                    val d = votes?.dislikes?.takeIf { showVotes }
                    if (d != null) Text(compact(d), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        val vt = votes?.takeIf { showVotes && it.likes + it.dislikes > 0 }
        if (vt != null) Box(Modifier.matchParentSize().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), contentAlignment = Alignment.BottomStart) {
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(vt.likes.toFloat() / (vt.likes + vt.dislikes)).height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)))
            }
        }
    }
}

/** Download → the percentage while it saves → Downloaded (it plays without internet) */
@Composable
private fun DownloadChip(app: AppState, v: Video, iconOnly: Boolean = false) {
    val saved by app.offline.saved.collectAsState()
    val p = app.offline.progress[v.id]
    when {
        v.id in saved.items -> Chip(Sym.DownloadDone.fill, tr("Downloaded")) { app.go(Screen.Downloads) }
        p != null -> Chip(Sym.Downloading.outline, "${(p * 100).toInt()}%") { app.go(Screen.Downloads) }
        else -> Chip(Sym.Download.outline, if (iconOnly) "" else tr("Download")) { app.offline.asking = v }
    }
}

/** A grey pill, 36dp tall: an icon and a short label */
@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).tvClickable(RoundedCornerShape(18.dp), sink = 0.94f, onClick = onClick).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = if (label.isEmpty()) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/** What sits under the video, by Settings → Under the video: everything, only the title, or nothing. */
private fun LazyListScope.under(app: AppState, v: Video, flush: Boolean = false) {
    when (app.store.settings.value.underVideo) {
        0 -> { item { Info(app, v, flush) }; item { SmartCard(app, v) } }
        1 -> item {
            Text(titleOf(v, app), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

/** The comments with a title and a close button: under the video on a phone, beside it in full screen. */
@Composable
fun CommentsPanel(app: AppState, token: String, info: Details? = app.playback.details, handle: Modifier = Modifier, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // a phone: a small grip on top, as YouTube's sheet; the header drags the panel down
        if (handle != Modifier) Box(Modifier.fillMaxWidth().then(handle).padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
        }
        Row(Modifier.fillMaxWidth().then(handle).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = info?.takeIf { it.commentsToken == token }?.commentsCount ?: ""
            Text(tr("Comments"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(count, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f))
            IconButton(onClick = onClose) { Icon(Sym.Close.fill, tr("Close")) }
        }
        HorizontalDivider()
        Comments(app, token, info)
    }
}

/** A scrolling list of comments that loads the next page by itself when you reach the end. */
@Composable
private fun Comments(app: AppState, token: String, info: Details? = app.playback.details) {
    var sorted by remember(token) { mutableStateOf(token) }
    val pager = rememberPager(sorted, { YouTube.comments(sorted) }, { YouTube.comments(it) })
    LazyColumn(Modifier.fillMaxSize()) { comments(app, pager, commentSorts(token, info), token, sorted) { sorted = it } }
}

/** "Top" and "Newest", as YouTube offers them for this video's comments; each is its own list */
private fun commentSorts(token: String, info: Details?) = info?.takeIf { it.commentsToken == token }?.commentSorts ?: emptyList()

/** the comments as items of a list, loading the next page when the end comes into view */
private fun LazyListScope.comments(
    app: AppState, pager: Pager<Comment>,
    sorts: List<Pair<String, String>> = emptyList(), token: String = "", sorted: String = "", sort: (String) -> Unit = {},
) {
    if (sorts.size > 1) item("sorts") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sorts.forEachIndexed { i, (title, t) ->
                val on = if (sorted == token) i == 0 else sorted == t
                Chip(on, onClick = { sort(if (i == 0) token else t) }, label = title)
            }
        }
    }
    // signed in: "Add a comment…"
    if (token.isNotEmpty()) item("compose") { CommentComposer(app, token) { pager.load() } }
    if (pager.items.isEmpty() && pager.loading) items(6) { BoneComment() }
    if (pager.items.isEmpty() && pager.started && !pager.loading) item { Empty(tr("No comments")) }
    items(pager.items, key = { "c" + it.id }) { CommentView(app, it) }
    if (pager.next != null) item("more") {
        // reaching the end asks for the next page
        LaunchedEffect(pager.items.size) { pager.loadMore() }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
    }
}

/** big: phones show related videos as full-width cards, wide screens as rows with larger pictures */
private fun LazyListScope.related(app: AppState, big: Boolean, thumb: Dp = 200.dp) {
    val pb = app.playback
    val queue = pb.queue
    if (queue.isNotEmpty()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(tr("Up next"), Modifier.weight(1f))
                // "Repeat playlist" (#91): after the last video the list starts again
                IconButton(onClick = { pb.loopList = !pb.loopList }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(if (pb.loopList) Sym.Repeat.fill else Sym.Repeat.outline, tr("Repeat playlist"),
                        tint = if (pb.loopList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(queue, key = { "q" + it.id }) { v ->
            // the handle at the end drags a video to another place in the queue (#85); the menu moves it one step
            val at = queue.indexOfFirst { it.id == v.id }
            var dy by remember(v.id) { mutableStateOf(0f) }
            var rowH by remember { mutableStateOf(1) }
            Row(
                Modifier.fillMaxWidth().onSizeChanged { rowH = it.height.coerceAtLeast(1) }
                    .zIndex(if (dy != 0f) 1f else 0f).graphicsLayer { translationY = dy },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    VideoRow(v, app, extra = listOfNotNull(
                        (tr("Move up") to { pb.moveInQueue(v, at - 1) }).takeIf { at > 0 },
                        (tr("Move down") to { pb.moveInQueue(v, at + 1) }).takeIf { at < queue.lastIndex },
                        tr("Remove from queue") to { pb.unqueue(v) },
                    ), thumbWidth = minOf(thumb, 168.dp)) { pb.open(v, queue.dropWhile { it.id != v.id }.drop(1)); app.stack[app.stack.size - 1] = Screen.Watch(v.id) }
                }
                if (queue.size > 1) Icon(Sym.DragHandle.outline, tr("Move up") + " / " + tr("Move down"), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp).padding(10.dp).pointerInput(v.id, at, queue.size) {
                        detectVerticalDragGestures(
                            onDragEnd = { val to = at + kotlin.math.round(dy / rowH).toInt(); dy = 0f; if (to != at) pb.moveInQueue(v, to) },
                            onDragCancel = { dy = 0f },
                        ) { ch, d -> ch.consume(); dy += d }
                    })
            }
        }
    }
    if (app.store.settings.value.hideRelated) return
    val d = pb.details ?: run { items(4) { BoneVideo(big, thumb) }; return }
    items(d.related.filter { !(app.noShorts && it is Video && it.short) }, key = { "r" + it.id }) {
        when (it) {
            is Video -> if (big) VideoCard(it, app) else VideoRow(it, app, thumbWidth = thumb)
            is Playlist -> PlaylistRow(it, app)
            is Channel -> ChannelRow(it, app)
        }
    }
}

/** A card under the buttons, as YouTube's: "Comments" and the count, then the first one's picture and two lines; a tap opens them all. */
@Composable
private fun CommentsTeaser(app: AppState, token: String, open: () -> Unit) {
    val pager = rememberPager(token, { YouTube.comments(token) }, { YouTube.comments(it) })
    val first = pager.items.firstOrNull()
    val count = app.playback.details?.takeIf { it.commentsToken == token }?.commentsCount ?: ""
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .tvClickable(onClick = open).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Comments"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (count.isNotEmpty()) Text(count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp))
        }
        if (first != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(first.avatar, 24)
            Text(first.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp))
        }
    }
}


@Composable
private fun CommentView(app: AppState, c: Comment, reply: Boolean = false) {
    var showReplies by remember { mutableStateOf(false) }
    val calm = app.store.settings.value.calm
    Row(Modifier.fillMaxWidth().padding(start = if (reply) 52.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
        Avatar(c.avatar, if (reply) 24 else 32, Modifier.clickable { if (c.channelId.isNotEmpty()) app.go(Screen.Channel(c.channelId, c.author)) })
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            // as YouTube: the name in the text colour, the time after it in grey
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.bodySmall, fontWeight = if (c.byCreator) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.published.isNotEmpty()) Text(c.published, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
            val translated = app.translations.shownText(c.id)
            SelectionContainer { Text(translated ?: c.text, style = MaterialTheme.typography.bodyMedium) }
            // as YouTube: Translate and the likes right under the text, then the replies as a small blue
            // button with an arrow that turns when they open (a full-height text button left a gap above)
            val lang = L.lang
            // as YouTube: only under a comment in another language (the device tells, where it can)
            val canTr = app.platform.canTranslate && remember(c.text, lang) {
                !needsNoTranslation(c.text, lang) && app.platform.textLanguage(c.text)?.substringBefore('-') != lang.substringBefore('-')
            }
            val likes = !calm && c.likes.isNotEmpty()
            // signed in: the comment's own like and reply, from the same comments page (YouTubeAccount.noteComments)
            val acts = if (app.feedSource.signedIn) YouTubeAccount.commentActions(c.id) else null
            var liked by remember(c.id, acts) { mutableStateOf(acts?.liked == true) }
            var replying by remember(c.id) { mutableStateOf(false) }
            if (canTr || likes || acts != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canTr) {
                    val scope = rememberCoroutineScope()
                    val busy = app.translations.busy(c.id)
                    // plain text, not a TextButton: its minimum width would push the word off the comment's left edge
                    Text(
                        tr(when { busy -> "Translating…"; translated != null -> "Show original"; else -> "Translate" }),
                        style = MaterialTheme.typography.labelLarge, color = Blue,
                        modifier = Modifier
                            .clickable(enabled = !busy) { scope.launch { app.translations.toggle(app.platform, c.id, c.text, lang, app::toast) } }
                            .padding(vertical = 4.dp),
                    )
                    if (likes) Spacer(Modifier.width(16.dp))
                }
                val likeAction = if (liked) acts?.unlike else acts?.like
                if (likes || likeAction != null) {
                    val scope = rememberCoroutineScope()
                    Row(
                        (if (likeAction == null) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                            // shown at once, put back with a toast when the account didn't take it
                            liked = !liked
                            scope.launch {
                                if (!runCatching { YouTubeAccount.commentAction(likeAction) }.getOrDefault(false)) {
                                    liked = !liked
                                    app.toast(tr("Couldn't update the like on your YouTube account"))
                                }
                            }
                        }).padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (liked) Sym.ThumbUp.fill else Sym.ThumbUp.outline, tr("Like"), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (likes) Text(c.likes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (acts?.replyParams != null) Text(
                    tr("Reply"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp).clickable { replying = !replying }.padding(vertical = 4.dp),
                )
            }
            val rp = acts?.replyParams
            if (replying && rp != null) ReplyBox(app, rp) { replying = false }
            if (c.replies > 0 && c.repliesToken != null) {
                val turn by androidx.compose.animation.core.animateFloatAsState(if (showReplies) -90f else 90f, tween(Motion.SHORT))
                Row(
                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).clickable { showReplies = !showReplies }
                        .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Sym.ChevronRight.outline, null, Modifier.size(18.dp).graphicsLayer { rotationZ = turn }, tint = Blue)
                    Text(tr("{n} replies").replace("{n}", "${c.replies}"), color = Blue, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    if (showReplies && c.repliesToken != null) {
        val pager = rememberPager(c.repliesToken, { YouTube.comments(c.repliesToken) }, { YouTube.comments(it) })
        val scope = rememberCoroutineScope()
        for (r in pager.items) CommentView(app, r, reply = true)
        if (pager.loading) Busy()
        if (pager.next != null && !pager.loading) TextButton(onClick = { scope.launch { pager.loadMore() } }, modifier = Modifier.padding(start = 52.dp)) {
            Text(tr("More replies"))
        }
    }
}


// ---------------------------------------------------------------------- the phone's watch sheet

/** the floating mini player (YouTube's 2025 one): this share of the screen's width, 16:9, this far from the edges, these corners */
private const val MINI_SHARE = 0.45f
private val MINI_MARGIN = 12.dp
private val MINI_R = 12.dp

/**
 * Phones: the video page as a sheet over the app, as in YouTube's app. The page under it
 * stays where it was. Pull the video down and it shrinks, following the finger, into the floating
 * mini player: a rounded card with just the video (play/pause top left, ✕ top right, a red line of
 * how far it got). Drag the card and it follows the finger, then springs to the nearest of the four
 * corners (flung past the side of the screen it closes); tap it and it grows back into the page.
 *
 * The video is laid out each frame at its in-between size (not scaled as a picture), so the video
 * surface underneath, a native view on every system, follows it exactly.
 */
@Composable
fun WatchSheet(app: AppState, expanded: Boolean, barPx: Int) {
    val pb = app.playback
    val v = pb.video ?: return
    val scope = rememberCoroutineScope()
    // 0: the whole page; 1: the mini player
    val f = remember { Animatable(if (expanded) 0f else 1f) }
    var held by remember { mutableStateOf(false) }
    val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
    LaunchedEffect(expanded) { if (!held) f.animateTo(if (expanded) 0f else 1f, settle) }
    // the corner the card rests in (0 top left, 1 top right, 2 bottom left, 3 bottom right) and, while
    // it is dragged or springing there, how far it is from that corner
    var corner by remember { mutableStateOf(3) }
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val gone = remember(v.id) { Animatable(1f) }
    // a new video (or the last one closed off the side) starts in its corner
    LaunchedEffect(v.id) { shift.snapTo(Offset.Zero) }
    val list = remember(v.id) { androidx.compose.foundation.lazy.LazyListState() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    // the app's root already keeps clear of the status bar (safe drawing padding) except in full screen:
    // counting it again here left a band as tall as the status bar above the video (#84)
    val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(d)
    val top = if (app.fullscreen) statusTop else 0
    val nav = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(d)
    val full by remember { androidx.compose.runtime.derivedStateOf { f.value < 0.02f } }
    val mini by remember { androidx.compose.runtime.derivedStateOf { f.value > 0.98f } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val videoH = w * 9f / 16f
        val margin = with(d) { MINI_MARGIN.toPx() }
        val miniW = (w * MINI_SHARE).coerceIn(with(d) { 168.dp.toPx() }, with(d) { 300.dp.toPx() })
        val miniH = miniW * 9f / 16f
        val radius = with(d) { MINI_R.toPx() }
        // the corners: under the top bar at the top, above the tabs at the bottom
        val yTop = top + with(d) { 64.dp.toPx() }
        val yBottom = h - maxOf(barPx, nav) - miniH - margin
        fun cornerAt(k: Int) = Offset(if (k % 2 == 0) margin else w - miniW - margin, if (k < 2) yTop else yBottom)
        val range = (yBottom - top).coerceAtLeast(1f)
        val fling = with(d) { 900.dp.toPx() }

        fun land(velocity: Float) {
            held = false
            val toMini = when {
                velocity > fling -> true
                velocity < -fling -> false
                else -> f.value > 0.5f
            }
            scope.launch { f.animateTo(if (toMini) 1f else 0f, settle, initialVelocity = velocity / range) }
            if (toMini == (app.current is Screen.Watch)) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
            if (toMini && app.current is Screen.Watch) app.back(hooks = false)
            else if (!toMini && app.current !is Screen.Watch) app.go(Screen.Watch(v.id))
        }

        /** the card let go at [shift] from its corner with [vel]: the nearest corner where it would come to rest, or off the side */
        fun drop(vel: Offset) {
            val now = cornerAt(corner) + shift.value
            // where the throw would carry it (a short glide), then the corner nearest that
            val aim = now + vel * 0.18f
            val cx = aim.x + miniW / 2
            if (cx < -miniW * 0.1f || cx > w + miniW * 0.1f) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                scope.launch {
                    val out = Offset(if (cx < w / 2) -miniW - margin * 2 - cornerAt(corner).x else w + margin - cornerAt(corner).x, shift.value.y)
                    launch { gone.animateTo(0f, tween(Motion.SHORT)) }
                    shift.animateTo(out, tween(Motion.SHORT, easing = Motion.emphasizedOut))
                    pb.close()
                }
                return
            }
            val k = (if (aim.y + miniH / 2 < (yTop + yBottom + miniH) / 2) 0 else 2) + (if (cx < w / 2) 0 else 1)
            if (k != corner) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
            val keep = now - cornerAt(k)
            corner = k
            scope.launch {
                shift.snapTo(keep)
                shift.animateTo(Offset.Zero, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 380f), initialVelocity = vel)
            }
        }

        /**
         * One handler for the whole sheet, so a drag that starts on the open page and ends in the
         * mini player (or the other way) is one gesture: on the page it follows the finger up and down;
         * the card follows it anywhere and snaps to a corner
         */
        fun Modifier.drag(): Modifier = pointerInput(range, v.id, w, h) {
            val track = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var acc = Offset.Zero
            var fromMini = false
            detectDragGestures(
                onDragStart = { acc = Offset.Zero; fromMini = f.value > 0.98f; if (!fromMini) held = true; track.resetTracking() },
                onDrag = { ch, delta ->
                    ch.consume()
                    acc += delta
                    track.addPosition(ch.uptimeMillis, acc)
                    scope.launch {
                        if (fromMini) shift.snapTo(shift.value + delta)
                        else f.snapTo((f.value + delta.y / range).coerceIn(0f, 1f))
                    }
                },
                onDragCancel = { if (fromMini) drop(Offset.Zero) else land(0f) },
                onDragEnd = {
                    val vel = track.calculateVelocity()
                    when {
                        fromMini -> drop(Offset(vel.x, vel.y))
                        // pushed up on the open page: full screen, as before (#64)
                        f.value < 0.01f && acc.y < -60.dp.toPx() -> { held = false; app.setFull(true) }
                        else -> land(vel.y)
                    }
                },
            )
        }

        // QA driver: "set minidrag -600,-900" moves the card that far (px) and lets go, as a finger would
        Parity.flags["minidrag"]?.let { a ->
            LaunchedEffect(a) {
                Parity.flags.remove("minidrag")
                val (dx, dy) = a.split(',').map { it.trim().toFloat() }
                shift.snapTo(Offset(dx, dy)); drop(Offset.Zero)
            }
        }
        val c = MaterialTheme.colorScheme
        // the page behind the video: fades as the sheet folds away, and holds the touches while open
        if (!mini) Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = (1f - f.value * 1.15f).coerceIn(0f, 1f) }.background(c.background)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (LocalLook.current == 3) Backdrop(v.thumb, Modifier.fillMaxWidth().height(560.dp))
            // Ambient mode's glow, behind what is under the video (the title stays sharp and white)
            if (!app.fullscreen) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(0, top) }
                .size(with(d) { w.toDp() }, with(d) { videoH.toDp() }).ambientGlow(AmbientFrame.now))
        }
        // what is under the video: comes along down and fades out early
        if (!mini) Box(
            Modifier.fillMaxSize().padding(top = with(d) { (top + videoH).toDp() })
                .graphicsLayer { translationY = f.value * range; alpha = (1f - f.value * 2.5f).coerceIn(0f, 1f) },
        ) { PhoneBody(app, v, list) }
        // the video: its size and place for this frame, worked out while laying out (nothing recomposes)
        val box = Modifier.layout { m, _ ->
            val k = f.value
            val at = cornerAt(corner) + shift.value * k
            val bw = (w + (miniW - w) * k).toInt().coerceAtLeast(1)
            val bh = (videoH + (miniH - videoH) * k).toInt().coerceAtLeast(1)
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(bw, bh))
            layout(p.width, p.height) {
                p.placeWithLayer((at.x * k).toInt(), (top + (at.y - top) * k).toInt()) {
                    alpha = gone.value
                    if (k > 0f) {
                        shape = RoundedCornerShape(radius * k); clip = true
                        shadowElevation = 8.dp.toPx() * k
                    }
                }
            }
        }
        Box(box.drag()) {
            PlayerBox(app, sheet = Modifier.fillMaxSize(), controls = full)
            // folded away: the card's own controls over the picture (a tap elsewhere opens the page)
            if (!full) MiniCard(app, mini, alpha = ((f.value - 0.7f) / 0.3f).coerceIn(0f, 1f)) { app.go(Screen.Watch(v.id)) }
        }
    }
}

/** the floating mini player's face: play/pause top left, ✕ top right, the red line along the bottom; a tap opens the page */
@Composable
private fun MiniCard(app: AppState, mini: Boolean, alpha: Float, open: () -> Unit) {
    val st by app.player.state.collectAsState()
    Box(Modifier.fillMaxSize().clickable(enabled = mini, onClick = open).graphicsLayer { this.alpha = alpha }) {
        if (alpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color(0x26000000)))
            // the round backdrop fades after a moment, the icon stays (YouTube's)
            var fresh by remember { mutableStateOf(true) }
            LaunchedEffect(st.playing) { fresh = true; kotlinx.coroutines.delay(2000); fresh = false }
            val back by androidx.compose.animation.core.animateFloatAsState(if (fresh || !st.playing) 0.55f else 0f, tween(400))
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = back))
                .clickable(enabled = mini) { if (st.ended) app.player.seekTo(0) else if (st.playing) app.player.pause() else app.player.play() },
                contentAlignment = Alignment.Center) {
                Icon(if (st.ended) Sym.Replay.fill else if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill,
                    tr(if (st.ended) "Replay" else if (st.playing) "Pause" else "Play"), Modifier.size(24.dp), tint = Color.White)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).clip(CircleShape).clickable(enabled = mini) { app.playback.close() },
                contentAlignment = Alignment.Center) {
                Icon(Sym.Close.fill, tr("Close"), Modifier.size(22.dp), tint = Color.White)
            }
            if (st.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Red))
            }
        }
    }
}

/**
 * Under the video on a phone: the details, the comments' card, what's next. The description, the
 * comments and the transcript open as YouTube's engagement panels: they rise over this space (the
 * video keeps playing above), a grip and a header with ✕ on top; dragged down by the top they follow
 * the finger and close past a quarter or on a flick.
 */
@Composable
private fun PhoneBody(app: AppState, v: Video, list: androidx.compose.foundation.lazy.LazyListState) {
    val pb = app.playback
    val set = app.store.settings.value
    val token = pb.details?.commentsToken?.takeIf { !set.hideComments }
    // "description", "comments", "transcript" or null
    var panel by remember(v.id) { mutableStateOf<String?>(null) }
    val hasCaptions = !pb.stream?.captions.isNullOrEmpty()
    LaunchedEffect(pb.transcriptAsked) {
        if (!pb.transcriptAsked) return@LaunchedEffect
        pb.transcriptAsked = false
        panel = "transcript"
    }
    // QA driver: "set panel comments|description|transcript"
    Parity.flags["panel"]?.let { want -> LaunchedEffect(want) { Parity.flags.remove("panel"); panel = want.takeIf { it != "0" } } }
    // Back closes an open panel before it folds the video away; only while the video page is in
    // front (folded into the mini player, its panels are put away and Back is the page's)
    val front = app.current is Screen.Watch
    LaunchedEffect(front) { if (!front) panel = null }
    BackHook(app, front && panel != null) { panel = null }
    // the last panel stays drawn while it slides away
    var shown by remember(v.id) { mutableStateOf<String?>(null) }
    if (panel != null) shown = panel
    Box(Modifier.fillMaxSize().riseIn(v.id)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOpenPanel provides { k: String -> panel = k }) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                item { PlayerNotes(app) }
                under(app, v)
                if (token != null) item { CommentsTeaser(app, token) { panel = "comments" } }
                related(app, big = true)
            }
        }
        val ok = when (panel) { "comments" -> token != null; "transcript" -> hasCaptions; null -> false; else -> true }
        EngagementPanel(ok, onClose = { panel = null }) { grip ->
            when (shown) {
                "comments" -> if (token != null) CommentsPanel(app, token, handle = grip) { panel = null }
                "transcript" -> Column(Modifier.fillMaxSize()) {
                    Grip(grip)
                    Box(Modifier.weight(1f)) { TranscriptPanel(app) { panel = null } }
                }
                "description" -> PanelFrame(tr("Description"), grip, { panel = null }) { DescriptionBody(app, v) { panel = null } }
            }
        }
    }
}

/** opens one of the phone watch page's panels ("description"...); null where there are none (the description is a sheet then) */
internal val LocalOpenPanel = androidx.compose.runtime.compositionLocalOf<((String) -> Unit)?> { null }

/**
 * A panel over the space under the phone's video, as YouTube's description and comments: it slides
 * up, and [content]'s top (the modifier it is handed) drags it down to close.
 */
@Composable
private fun EngagementPanel(open: Boolean, onClose: () -> Unit, content: @Composable (Modifier) -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        open,
        enter = androidx.compose.animation.slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } +
            androidx.compose.animation.fadeIn(tween(Motion.SHORT)),
        exit = androidx.compose.animation.slideOutVertically(tween(Motion.SHORT + 50, easing = Motion.emphasizedOut)) { it } +
            androidx.compose.animation.fadeOut(tween(Motion.SHORT + 50)),
    ) {
        val scope = rememberCoroutineScope()
        val down = remember { Animatable(0f) }
        var tall by remember { mutableStateOf(1f) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val grip = Modifier.pointerInput(Unit) {
            val track = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var acc = 0f
            detectVerticalDragGestures(
                onDragStart = { acc = 0f; track.resetTracking() },
                onVerticalDrag = { ch, dy ->
                    ch.consume(); acc += dy; track.addPosition(ch.uptimeMillis, Offset(0f, acc))
                    scope.launch { down.snapTo((down.value + dy).coerceAtLeast(0f)) }
                },
                onDragCancel = { scope.launch { down.animateTo(0f) } },
                onDragEnd = {
                    val vy = track.calculateVelocity().y
                    scope.launch {
                        if (down.value > tall * 0.25f || vy > 900.dp.toPx()) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                            onClose(); delay(400); down.snapTo(0f)
                        } else down.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 500f), initialVelocity = vy)
                    }
                },
            )
        }
        androidx.compose.material3.Surface(
            Modifier.fillMaxSize().onSizeChanged { tall = it.height.toFloat().coerceAtLeast(1f) }.graphicsLayer { translationY = down.value },
            color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), shadowElevation = 2.dp,
        ) { content(grip) }
    }
}

/** the small grip on top of a panel; dragging it (or the header) pulls the panel down */
@Composable
private fun Grip(handle: Modifier) {
    Box(Modifier.fillMaxWidth().then(handle).padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
    }
}

/** a panel's grip, its title and ✕, a line, then [body] */
@Composable
private fun PanelFrame(title: String, handle: Modifier, onClose: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Grip(handle)
        Row(Modifier.fillMaxWidth().then(handle).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Sym.Close.fill, tr("Close")) }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) { body() }
    }
}


/** the theater mode of the website layout: the video across the window, the details and up next under it */
object WebWatch {
    var theater by mutableStateOf(false)
    init {
        // the player's settings menu gets a row for it (key: T)
        PlayerMenuExtras.add(PlayerMenuExtras.Extra("theater", row = { app, _, dismiss ->
            if (webDesk(app)) PlayerMenuRow(WebIcons.Theater, tr("Theater mode"), if (theater) tr("On") else tr("Off")) { theater = !theater; dismiss() }
        }))
    }
}

/**
 * The watch page as on YouTube's website: the video with its title, channel, buttons and description,
 * the comments under them, and on the right the live chat (live or replay), the transcript when
 * asked, then chips (All / From the channel / Related) over what's next. Theater: the video across
 * the window on black, the rest under it.
 */
@Composable
private fun WebWatchBody(
    app: AppState, v: Video, token: String?, hasRelated: Boolean, hasCaptions: Boolean,
    transcriptOpen: Boolean, setTranscript: (Boolean) -> Unit, maxWidth: Dp, maxHeight: Dp,
) {
    WebWatch.theater // registers the menu row
    if (Parity.flags["theater"] != null) LaunchedEffect(Parity.flags["theater"]) { WebWatch.theater = Parity.flags["theater"] == "1"; Parity.flags.remove("theater") }
    if (Parity.on("transcript") && !transcriptOpen) LaunchedEffect(Unit) { Parity.flags.remove("transcript"); setTranscript(true) }
    val theater = WebWatch.theater
    val d = app.playback.details
    val chatLikely = v.live || d?.publishedText?.let { it.contains("Stream", true) || it.contains("Premiere", true) || it.contains("live", true) } == true
    val sideW = (maxWidth * 0.3f).coerceIn(300.dp, 402.dp)
    var sorted by remember(token) { mutableStateOf(token) }
    val pager = token?.let { t -> rememberPager(sorted ?: t, { YouTube.comments(sorted ?: t) }, { YouTube.comments(it) }) }
    @Composable
    fun Side(m: Modifier) {
        // the chat stays put at the top; what's next scrolls under it
        Column(m) {
        if (chatLikely) LiveChatPanel(app, v.id, minOf(if (theater) 360.dp else 520.dp, maxHeight * 0.6f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (transcriptOpen && hasCaptions) item("transcript") {
                Box(Modifier.fillMaxWidth().height(minOf(520.dp, maxHeight - 120.dp)).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                    TranscriptPanel(app) { setTranscript(false) }
                }
            }
            if (hasRelated) webRelated(app, v)
        }
        }
    }
    @Composable
    fun Details(m: Modifier, withPlayer: Boolean) {
        LazyColumn(m, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (withPlayer) item("player") { Box(Modifier.clip(RoundedCornerShape(12.dp))) { PlayerBox(app) } }
            under(app, v, flush = true)
            if (pager != null && token != null) {
                item("ctitle") {
                    val count = d?.takeIf { it.commentsToken == token }?.commentsCount ?: ""
                    Text(listOf(count, tr("Comments")).filter { it.isNotEmpty() }.joinToString(" "), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
                }
                comments(app, pager, commentSorts(token, d), token, sorted ?: token) { sorted = it }
            }
        }
    }
    if (theater) {
        Column(Modifier.fillMaxSize()) {
            val h = minOf(maxWidth * 9f / 16f, maxHeight * 0.74f)
            Box(Modifier.fillMaxWidth().height(h).background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) { PlayerBox(app) }
            }
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Details(Modifier.weight(1f), withPlayer = false)
                Side(Modifier.width(sideW))
            }
        }
    } else {
        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Details(Modifier.weight(1f), withPlayer = true)
            Side(Modifier.width(sideW))
        }
    }
}

/** up next as on the website: chips (All, From the channel, Related), then compact rows */
private fun LazyListScope.webRelated(app: AppState, v: Video) {
    val pb = app.playback
    if (pb.queue.isNotEmpty()) { related(app, big = false, thumb = 168.dp); return }
    if (app.store.settings.value.hideRelated) return
    item("rchips") {
        val chip = WebRelatedChip.chip
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WebChip(tr("All"), chip == 0) { WebRelatedChip.chip = 0 }
            if (v.author.isNotEmpty()) WebChip(tr("From {c}").replace("{c}", v.author), chip == 1) { WebRelatedChip.chip = 1 }
            WebChip(tr("Related"), chip == 2) { WebRelatedChip.chip = 2 }
        }
    }
    val d = pb.details ?: run { items(4) { BoneVideo(false, 168.dp) }; return }
    val list = d.related.filter { !(app.noShorts && it is Video && it.short) }.filter {
        when (WebRelatedChip.chip) {
            1 -> it is Video && it.channelId == v.channelId
            2 -> !(it is Video && it.channelId == v.channelId)
            else -> true
        }
    }
    if (list.isEmpty()) item("rnone") { Empty(tr("Nothing here")) }
    items(list, key = { "r" + it.id }) {
        when (it) {
            is Video -> VideoRow(it, app, thumbWidth = 168.dp)
            is Playlist -> PlaylistRow(it, app)
            is Channel -> ChannelRow(it, app)
        }
    }
}

private object WebRelatedChip { var chip by mutableStateOf(0) }
