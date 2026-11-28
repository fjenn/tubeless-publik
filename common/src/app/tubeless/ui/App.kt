package app.tubeless.ui

import app.tubeless.Brand
import app.tubeless.engine.WriteLimit
import app.tubeless.engine.YouTubeAccount

import app.tubeless.engine.YouTube
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import app.tubeless.engine.Sys
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.InnerTube
import app.tubeless.engine.Net
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.request.crossfade

/** true on a phone-sized touch screen: the bottom bar, edge-to-edge cards; false on tablets and desktops */
val LocalCompact = compositionLocalOf { true }

@Composable
fun App(app: AppState) {
    val settings by app.store.settings.state.collectAsState()
    val lang = settings.lang.ifEmpty { "en" }
    L.lang = lang
    InnerTube.hl = if (lang == "zh") "zh-CN" else lang
    InnerTube.gl = settings.region.ifEmpty { app.platform.deviceRegion() }.uppercase()
    LaunchedEffect(Unit) { YouTube.force = settings.playMethod }
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { Sys.imageNetwork(this); add(Thumbs.Sized(app.platform.weak)) }   // pictures at the size shown (Thumbs)
            .diskCache { DiskCache.Builder().fileSystem(Sys.fs).directory(app.platform.cacheDir / "images").maxSizeBytes(150L shl 20).build() }
            // a small TV stick: a smaller picture memory and no fades, so scrolling a row doesn't stall
            // a computer: at most 64 MB of decoded pictures (25 % of the JVM's 512 MB was 128 MB of native memory,
            // more than a screen of thumbnails needs; M3 memory work), else a share of the device's memory
            .memoryCache {
                if (!app.platform.touch && !app.platform.tv && app.platform.name == "desktop") coil3.memory.MemoryCache.Builder().maxSizeBytes(64L shl 20).build()
                else coil3.memory.MemoryCache.Builder().maxSizePercent(ctx, if (app.platform.weak) 0.12 else 0.25).build()
            }
            .crossfade(!app.platform.weak)
            .build()
    }
    key(lang, settings.tvMode) {
        TubeTheme(settings.dark, app.platform::wallpaperColors, app.platform.font, look = if (app.platform.tv) 0 else settings.look, night = settings.nightTheme) {
            val bg = MaterialTheme.colorScheme.background
            LaunchedEffect(bg) { app.platform.titleBar(bg) }
            val tv = app.platform.tv
            // every haptic in the app goes through here: off by the setting, else the platform's own motor
            val host = androidx.compose.ui.platform.LocalHapticFeedback.current
            val hapticsOn by androidx.compose.runtime.rememberUpdatedState(settings.haptics && !tv)
            val haptic = remember(host) {
                object : androidx.compose.ui.hapticfeedback.HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
                        if (!hapticsOn) return
                        if (!app.platform.vibrate(hapticFeedbackType)) host.performHapticFeedback(hapticFeedbackType)
                    }
                }
            }
            CompositionLocalProvider(
                LocalPlatform provides app.platform,
                androidx.compose.ui.platform.LocalHapticFeedback provides haptic,
                // TV: buttons, tabs and menu rows the remote has selected glow teal, clearly enough for the sofa
                LocalRippleConfiguration provides if (tv) RippleConfiguration(Teal, RippleAlpha(0.16f, 0.34f, 0.08f, 0.2f)) else LocalRippleConfiguration.current,
            ) {
                Surface(
                    Modifier.fillMaxSize().let { if (tv) it.onPreviewKeyEvent { e -> tvKey(app, e) } else it },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ParityDriver(app)
                    // M3 E2E: the first frame of the app is drawn
                    LaunchedEffect(Unit) { E2E.log("app_ready", "platform" to app.platform.name, "signedIn" to YouTubeAccount.signedIn) }
                    // smoke/E2E: each page as it comes to the front (Home, Search, Watch, Shorts, Settings...)
                    LaunchedEffect(app.current) { E2E.log("screen", "name" to (app.current::class.simpleName ?: "?")) }
                    // a write to the account went over the pace limit (S4's WriteLimit): say so rather than drop it silently
                    LaunchedEffect(Unit) {
                        WriteLimit.onLimited = { app.toast(tr("Slow down a little: that change wasn't sent to your account. Try again in a minute.")) }
                    }
                    // the bell (S7's panel; S4 sets Notifications.source with the data)
                    if (TopBarSlots.bell == null && !tv) TopBarSlots.bell = { a -> NotificationBell(a) }
                    // phones get the phone layout; tablets and the desktop the wide one
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val d = LocalDensity.current
                        // iPhone: full screen when turned sideways while a video plays, out of it upright (#58);
                        // Android phones do the same in MainActivity
                        if (app.platform.name == "ios") {
                            val landscape = maxWidth > maxHeight
                            val phone = minOf(maxWidth, maxHeight) < 600.dp
                            LaunchedEffect(landscape) {
                                if (!phone || !app.store.settings.value.rotateFull) return@LaunchedEffect
                                if (landscape && !app.fullscreen && app.current is Screen.Watch && app.playback.video != null) app.setFull(true)
                                else if (!landscape && app.fullscreen) app.setFull(false)
                            }
                        }
                        CompositionLocalProvider(
                            LocalCompact provides (app.platform.touch && maxWidth < 600.dp),
                            LocalDensity provides Density(d.density, d.fontScale * settings.textScale),
                        ) {
                            // first start (SHORTS-ONBOARD): the device's language on every app (no picker; English when
                            // the app has no translation for it), then one calm page: Shorts on or off, and the sign-in.
                            // A TV opens straight on Home. The website sets both itself (web/Main.kt) and never sees it.
                            if (settings.lang.isEmpty() || (tv && !settings.welcomed)) LaunchedEffect(Unit) {
                                val device = app.platform.deviceLanguage().substringBefore('-').lowercase()
                                app.store.settings.update { s ->
                                    s.copy(lang = s.lang.ifEmpty { LANGS.firstOrNull { it.first == device }?.first ?: "en" }, welcomed = s.welcomed || tv)
                                }
                            }
                            when {
                                settings.lang.isEmpty() -> Box(Modifier.fillMaxSize())
                                !settings.welcomed && !tv && app.platform.name != "web" && app.store.lib.value.subs.isEmpty() -> Welcome(app)
                                app.pip -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                                    app.player.View(Modifier.fillMaxSize())
                                }
                                // a TV: its own big-screen layout and player
                                tv -> TvApp(app)
                                // a phone or computer the household shares: "Who's watching?" when it opens
                                !app.whoAsked && app.store.people.value.askOnOpen && app.store.people.value.names.size > 1 -> WhoIsWatching(app, app.store.people.value)
                                app.fullscreen && app.current is Screen.Watch -> FullscreenWatch(app)
                                else -> Shell(app)
                            }
                            // Google's sign-in page inside the window (desktop), over everything while it is open
                            InAppSignIn(app)
                        }
                    }
                    LaunchedEffect(Unit) {
                        app.fillSubInfo()
                        app.sync.refreshSoon()
                        app.whatsNewAfterUpdate()
                        // at start, and every hour for a window left open (Android also asks each time
                        // the app comes back to the front: MainActivity.onStart)
                        while (true) { app.checkForUpdates(); kotlinx.coroutines.delay(3_600_000) }
                    }
                    LaunchedEffect(settings.hideBars) { app.platform.setImmersive(settings.hideBars) }
                    LaunchedEffect(settings.longBuffer, settings.bufferMb) { app.player.setReadAhead(settings.longBuffer); app.player.setBufferMb(settings.bufferMb) }
                    LaunchedEffect(settings.captionSize, settings.captionBg, settings.captionPos) {
                        app.player.setCaptionStyle(settings.captionSize, settings.captionBg, settings.captionPos)
                    }
                    LaunchedEffect(settings.audioDevice) { app.player.setAudioDevice(settings.audioDevice) }
                    app.update?.let { UpdateDialog(app, it) }
                    if (app.casting.picking) CastDialog(app)
                    app.whatsNew?.let { WhatsNewDialog(app, it) }
                    app.reminder?.let { ReminderDialog(app, it) }
                    app.offline.asking?.let { if (app.platform.instant) WebSaveDialog(app, it) { app.offline.asking = null } else OfflineDialog(app, it) }
                    FullMode.asking?.let { FullModeDialog(app, it) { FullMode.asking = null } }
                    if (app.platform.instant) CodeSignInDialog(app)
                }
            }
        }
    }
}

/** TV remote keys for every screen: the search key, and Shorts (up and down change the Short, OK pauses) */
private fun tvKey(app: AppState, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.Search) { app.go(Screen.Search()); return true }
    val shorts = app.shortsKeys
    if (app.current is Screen.Shorts && shorts != null) return when (e.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> playerKey(app, Key.Spacebar)
        else -> shorts(e.key)
    }
    return false
}

private data class Tab(val screen: Screen, val label: String, val on: ImageVector, val off: ImageVector)

/**
 * The tabs: on a phone, as in YouTube's app now, the bottom bar is Home, Shorts and Library, and
 * Subscriptions moved to the tabs at the top (TopTabs); the side menu of tablets, desktops and TVs keeps all four.
 */
@Composable
private fun tabs(app: AppState, phone: Boolean): List<Tab> {
    val set by app.store.settings.state.collectAsState()
    return listOfNotNull(
        if (set.homeOff && !phone) null else Tab(Screen.Home, tr("Home"), Sym.Home.fill, Sym.Home.outline),
        if (app.noShorts) null else Tab(Screen.Shorts, tr("Shorts"), Sym.Bolt.fill, Sym.Bolt.outline),
        if (phone) null else Tab(Screen.Subs, tr("Subscriptions"), Sym.Subscriptions.fill, Sym.Subscriptions.outline),
        if (set.hideMusicTab) null else Tab(Screen.Music, tr("Music"), Sym.Music.fill, Sym.Music.outline),
        Tab(Screen.Library, tr("Library"), Sym.VideoLibrary.fill, Sym.VideoLibrary.outline),
    )
}

@Composable
private fun Shell(app: AppState) {
    val now = app.current
    val compact = LocalCompact.current
    val root = app.stack.firstOrNull()
    // leaving the video page: as set, the mini player, or paused, or stopped
    val wasWatch = remember { mutableStateOf(false) }
    LaunchedEffect(now) {
        val w = now is Screen.Watch
        if (wasWatch.value && !w && now !is Screen.NowPlaying && !app.platform.touch) when (app.store.settings.value.leaveVideo) {
            1 -> app.player.pause()
            2 -> app.playback.close()
        }
        wasWatch.value = w
    }
    // phones: the video page is a sheet over the page it was opened from, which stays put under it
    // and is what the rest of the shell lays out (WatchSheet)
    val sheet = compact && app.platform.touch && !app.platform.tv && app.playback.video != null && app.playback.song == null
    val cur = if (sheet && now is Screen.Watch) app.stack.lastOrNull { it !is Screen.Watch } ?: Screen.Home else now
    var barPx by remember { mutableStateOf(0) }
    // a computer: the website's layout (masthead on every page, the guide, the mini guide)
    val web = webDesk(app)
    val showTop = if (web) cur !is Screen.NowPlaying else cur !is Screen.Watch && cur !is Screen.Shorts && cur !is Screen.Search && cur !is Screen.NowPlaying &&
        // a phone's Settings has its own plain "← Settings" header, as YouTube's app
        !(compact && (cur is Screen.Settings || cur is Screen.SettingsPage))
    // the watch page: the guide is a drawer over the page, opened from the masthead's menu button
    var drawer by remember { mutableStateOf(false) }
    LaunchedEffect(cur) { drawer = false }
    // the desktop: while you browse, the video keeps playing in a small window at the bottom right
    val floating = !app.platform.touch && !app.platform.tv
    // dragged with the mouse: how far from that corner it was moved, kept inside the window
    var floatAt by remember { mutableStateOf(Offset.Zero) }
    var floatArea by remember { mutableStateOf(IntSize.Zero) }
    val floatMargin = with(LocalDensity.current) { 32.dp.toPx() }
    val moveFloat = { d: Offset, card: IntSize ->
        val maxX = (floatArea.width - card.width - floatMargin).coerceAtLeast(0f)
        val maxY = (floatArea.height - card.height - floatMargin).coerceAtLeast(0f)
        floatAt = Offset((floatAt.x + d.x).coerceIn(-maxX, 0f), (floatAt.y + d.y).coerceIn(-maxY, 0f))
    }
    // touch screens: the tabs (bottom on phones, the side rail on tablets) and the top bar slide away
    // while scrolling down and come back scrolling up
    val autoHide = app.platform.touch && app.store.settings.state.collectAsState().value.autoHideNav
    var navShown by remember { mutableStateOf(true) }
    // the side menu folded away: a small menu button brings it back (in the top bar, or above the video)
    val railCollapsed = !compact && app.store.settings.state.collectAsState().value.railCollapsed
    val unfold = { app.store.settings.update { it.copy(railCollapsed = false) } }
    // a computer: the menu button sits left of the logo in the top bar, as on YouTube's website
    val deskBar = !compact && !app.platform.touch && !app.platform.tv
    val fold = { app.store.settings.update { it.copy(railCollapsed = true) } }
    val navScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) navShown = false else if (available.y > 6f) navShown = true
                return Offset.Zero
            }
        }
    }
    // a tab tapped again scrolls its page to the top: the top bar comes back with it
    LaunchedEffect(app.reselect) { navShown = true }
    LaunchedEffect(now) {
        navShown = true
        // Shorts has its own player; a video playing in the background stops
        if (now is Screen.Shorts) app.playback.close()
    }
    // the window's width (a computer): YouTube's guide is full from 1312 px, the mini guide below that, and
    // below 792 px it is only a drawer from the menu button
    var winW by remember { mutableStateOf(2000.dp) }
    val dens = LocalDensity.current
    val guideDrawer = web && winW < 792.dp
    val guideMini = railCollapsed || winW < 1312.dp
    Box(Modifier.fillMaxSize().onSizeChanged { winW = with(dens) { it.width.toDp() } }) {
    Scaffold(
        topBar = { if (showTop) AnimatedVisibility(navShown || !autoHide, enter = expandVertically(), exit = shrinkVertically()) { TopBar(app, if (web) ({ if (cur is Screen.Watch || guideDrawer || (winW < 1312.dp && !railCollapsed)) drawer = !drawer else app.store.settings.update { it.copy(railCollapsed = !it.railCollapsed) } }) else if (railCollapsed) unfold else if (deskBar) fold else null, page = cur, depth = if (sheet) app.stack.count { it !is Screen.Watch } else app.stack.size) } },
        bottomBar = {
            Column {
                // the mini player rises from the bottom edge and sinks back into it (phones, tablets, TVs;
                // the desktop has a floating one with the picture, below)
                AnimatedVisibility(
                    app.playback.video != null && cur !is Screen.Watch && cur !is Screen.Shorts && cur !is Screen.NowPlaying && !floating && !sheet,
                    enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) { it } + fadeIn(tween(Motion.SHORT)),
                    exit = slideOutVertically(tween(Motion.SHORT, easing = Motion.emphasizedOut)) { it } + fadeOut(tween(Motion.SHORT)),
                ) { MiniPlayer(app) }
                // (the sheet's mini player floats over the page in a corner: no room is kept for it)
                Box(Modifier.onSizeChanged { barPx = it.height }) {
                    // the phone's tabs stay put while scrolling, as YouTube's app (only the top bar slides away)
                    // (not over the keyboard while typing a search: YouTube's app gives the suggestions that room)
                    val typing = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(dens) > 0
                    if (compact && cur !is Screen.Watch && cur !is Screen.NowPlaying && !typing) PhoneTabBar(app, root)
                }
            }
        },
        // (on Shorts the notes show at the top instead: over the bar they covered the Short's title and channel)
        snackbarHost = { if (cur !is Screen.Shorts) SnackbarHost(app.snackbar) },
    ) { pad ->
        Row(Modifier.fillMaxSize().padding(pad).let { if (autoHide) it.nestedScroll(navScroll) else it }) {
            // tablets: the side rail steps aside while watching, so the video gets the room
            if (web) { if (cur !is Screen.Watch && cur !is Screen.NowPlaying && !guideDrawer) DeskGuide(app, cur, mini = guideMini) }
            else if (!compact && !(cur is Screen.Watch && app.platform.touch) && !railCollapsed) AnimatedVisibility(navShown || !autoHide, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.background,
                    header = {
                        if (!(deskBar && showTop)) IconButton(onClick = { app.store.settings.update { it.copy(railCollapsed = true) } }) {
                            Icon(Sym.MenuOpen.fill, tr("Fold the menu"))
                        }
                        // the desktop goes back with the mouse's side buttons and Alt+arrows, no buttons here
                    },
                ) {
                    for (t in tabs(app, phone = false)) {
                        val sel = t.screen.lit(root)
                        NavigationRailItem(
                            selected = sel, onClick = { app.go(t.screen) },
                            icon = { Icon(if (sel) t.on else t.off, null) },
                            // as on the web: small labels, the open page bold, its filled icon in a soft teal pill
                            label = { Text(t.label, fontSize = 11.sp, maxLines = 1,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = accentSoft,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    // below a line, smaller: back to the last video, and the settings
                    HorizontalDivider(Modifier.width(40.dp).padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
                    val last = app.playback.video ?: app.store.lib.state.collectAsState().value.hist.firstOrNull()?.video()
                    if (last != null) SmallRailItem(Sym.History.outline, tr("Resume"), cur is Screen.Watch) {
                        if (app.playback.video?.id == last.id) app.go(Screen.Watch(last.id)) else app.watch(last)
                    }
                    SmallRailItem(Sym.Settings.outline, tr("Settings"), cur == Screen.Settings) { app.go(Screen.Settings) }
                    // the video page has no top bar, so search sits here too
                    if (!showTop) SmallRailItem(Sym.Search.outline, tr("Search"), cur is Screen.Search) { app.go(Screen.Search()) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
              // the watch page has no top bar: the folded menu's button sits in a slim row above the video
              if (railCollapsed && cur is Screen.Watch && !app.platform.touch && !web) {
                  IconButton(onClick = unfold, modifier = Modifier.padding(start = 4.dp).size(36.dp)) { Icon(Sym.Menu.fill, tr("Menu")) }
              }
              Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { floatArea = it }) {
                NearbyAsk(app)
                AutoSaveRunner(app)
                key(cur) {
                  // a new page fades in: going forward it slides in from the right, going back from the
                  // left, a tab settles from a touch smaller (not the watch page or Shorts: a video
                  // surface shouldn't be see-through or moving while it starts)
                  val move = remember { app.lastMove }
                  val shown = remember { Animatable(if (cur is Screen.Watch || cur is Screen.Shorts) 1f else 0f) }
                  LaunchedEffect(Unit) { shown.animateTo(1f, if (move == 0) tween(400, easing = Motion.out) else tween(Motion.MEDIUM, easing = Motion.emphasizedIn)) }
                  Box(Modifier.fillMaxSize().graphicsLayer {
                      val rest = 1f - shown.value
                      alpha = shown.value
                      when (move) {
                          // the music player rises from the mini player, as a sheet
                          1 -> if (cur is Screen.NowPlaying) translationY = rest * 72.dp.toPx() else translationX = rest * 40.dp.toPx()
                          -1 -> translationX = -rest * 24.dp.toPx()
                          // a tab: the page rises in a little, as on the web
                          else -> translationY = rest * 10.dp.toPx()
                      }
                  }) { key(app.reload.value) {
                    when (cur) {
                        Screen.Home -> HomeScreen(app)
                        Screen.Shorts -> ShortsScreen(app)
                        Screen.Subs -> if (webDesk(app)) WebSubsScreen(app) else SubsScreen(app)
                        Screen.Library -> LibraryScreen(app)
                        Screen.Settings -> SettingsScreen(app)
                        Screen.Learned -> LearnedScreen(app)
                        Screen.ManageSubs -> ManageSubsScreen(app)
                        Screen.History -> HistoryScreen(app)
                        is Screen.Search -> SearchScreen(app, cur.query)
                        is Screen.Watch -> WatchScreen(app)
                        is Screen.Channel -> ChannelScreen(app, cur.id, cur.name)
                        is Screen.Playlist -> PlaylistScreen(app, cur.id, cur.title)
                        is Screen.Local -> LocalListScreen(app, cur.key)
                        Screen.Downloads -> DownloadsScreen(app)
                        is Screen.Topic -> TopicScreen(app, cur.key)
                        Screen.Music -> MusicScreen(app)
                        Screen.NowPlaying -> NowPlayingScreen(app)
                        is Screen.Album -> AlbumScreen(app, cur.id, cur.title)
                        is Screen.Artist -> ArtistScreen(app, cur.id, cur.name)
                        is Screen.SettingsPage -> SettingsPageScreen(app, cur.key)
                        Screen.Stats -> StatsScreen(app)
                    } }
                  }
                }
                // the plain one: inside a Box that sits in a Column, the Column version would be picked
                androidx.compose.animation.AnimatedVisibility(
                    floating && app.playback.video != null && cur !is Screen.Watch && cur !is Screen.Shorts && cur !is Screen.NowPlaying,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).offset { IntOffset(floatAt.x.roundToInt(), floatAt.y.roundToInt()) },
                    enter = fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM, easing = Motion.emphasizedIn), initialScale = 0.9f),
                    exit = fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.9f),
                ) { FloatingPlayer(app, moveFloat) }
              }
            }
        }
    }
    if (sheet && cur !is Screen.Shorts) WatchSheet(app, expanded = now is Screen.Watch, barPx = barPx)
    if (cur is Screen.Shorts) SnackbarHost(app.snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 52.dp))
    if (web && drawer) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { drawer = false })
        Surface(Modifier.width(240.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
            Column {
                Row(Modifier.height(56.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drawer = false }) { Icon(Sym.Menu.fill, tr("Menu")) }
                    Logo(Modifier.padding(start = 4.dp).clickable { drawer = false; app.go(Screen.Home) })
                }
                DeskGuide(app, cur, mini = false) { drawer = false }
            }
        }
    }
    }
}

/** whether this tab is the open one: Home also for the pages of the top tabs that sit under it (Music, Live; Subscriptions on phones) */
private fun Screen.lit(root: Screen?): Boolean = when {
    root == null -> false
    this == Screen.Home -> root == Screen.Home || (root is Screen.Topic && root.key in TOP_TOPICS)
    else -> root == this
}

/**
 * The phone's bottom bar as the web app 1.0 draws it: no line on top, the icons and small labels close
 * together, the open one filled in a soft teal pill that pops in. Subscriptions lights Home, as it is one
 * of Home's top tabs here.
 */
@Composable
private fun PhoneBar(app: AppState, root: Screen?) {
    val c = MaterialTheme.colorScheme
    // the cards and glass looks: the bar floats, a rounded pill a little above the bottom edge
    val look = LocalLook.current
    val pillShape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth().background(c.background).windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
        // the 1.5 design: flat along the bottom with a hairline on top, as YouTube's
        if (look == 3) HorizontalDivider(color = c.outline, thickness = 0.5.dp)
        val soft = accentSoft
        Row(
            Modifier.fillMaxWidth().let {
                if (look == 0 || look == 3) it else it.padding(horizontal = 14.dp, vertical = 6.dp).clip(pillShape)
                    .background(c.surfaceContainerHigh)
                    .border(1.dp, if (look == 2) (if (c.background.luminance() < 0.5f) Color(0x1FFFFFFF) else Color.White) else Color.Transparent, pillShape)
            }.height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in tabs(app, phone = true)) {
                val sel = t.screen.lit(root) || (t.screen == Screen.Home && root == Screen.Subs)
                // pressed as in YouTube's app: a grey circle swells behind the icon, which dips and
                // springs back when let go
                val touch = remember { MutableInteractionSource() }
                val pressed by touch.collectIsPressedAsState()
                val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                val dip by animateFloatAsState(if (pressed) 0.86f else 1f,
                    if (pressed) tween(Motion.SHORT) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
                val shade by animateFloatAsState(if (pressed) 0.12f else 0f, tween(if (pressed) 90 else Motion.LONG))
                val ink = c.onSurface
                // the pill behind the open tab grows in with a little spring (the web's navPop)
                val pill by animateFloatAsState(if (sel) 1f else 0f,
                    if (sel) spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow) else tween(Motion.SHORT))
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(sel, interactionSource = touch, indication = null, role = Role.Tab, onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); app.go(t.screen) }),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 30.dp).drawBehind {
                            if (shade > 0f) drawRoundRect(ink.copy(alpha = shade), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                            if (pill > 0.01f) {
                                val p = pill.coerceAtMost(1.15f)
                                val w = size.width * (0.6f + 0.4f * p); val h = size.height * (0.82f + 0.18f * p)
                                drawRoundRect(soft.copy(alpha = soft.alpha * pill.coerceIn(0f, 1f)), topLeft = Offset((size.width - w) / 2, (size.height - h) / 2),
                                    size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (sel) t.on else t.off, null, Modifier.size(24.dp).graphicsLayer { scaleX = dip; scaleY = dip },
                            tint = if (sel) c.onSurface else c.onSurfaceVariant)
                    }
                    Text(t.label, fontSize = 11.sp, maxLines = 1, color = if (sel) c.onSurface else c.onSurfaceVariant,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

/** A quieter item at the bottom of the side menu (Resume, Settings): a smaller icon and label */
@Composable
private fun SmallRailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Full screen; on a wide screen the comments can open beside the video (the button at the top right). */
@Composable
private fun FullscreenWatch(app: AppState) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val token = app.playback.details?.commentsToken?.takeIf { !app.store.settings.value.hideComments }
        val canSide = maxWidth > 600.dp && token != null
        val panel = minOf(400.dp, maxWidth * 0.35f)
        CompositionLocalProvider(LocalSideComments provides canSide) {
            Row(Modifier.fillMaxSize()) {
                VideoArea(app, Modifier.weight(1f).fillMaxHeight(), fullscreen = true)
                if (canSide && app.fsComments) {
                    Surface(Modifier.width(panel).fillMaxHeight(), color = MaterialTheme.colorScheme.background) {
                        CommentsPanel(app, token!!) { app.fsComments = false }
                    }
                }
            }
        }
    }
}

/** true when full screen can show the comments beside the video (a wide screen, a video with comments) */
val LocalSideComments = compositionLocalOf { false }

/** the brand amber of the logo's "less" box (#F7971D, measured from the reference two-part logo) */
val BrandAmber = Color(0xFFF7971D)

private fun svgPath(d: String) = PathParser().parsePathString(d).toPath()

/**
 * The Tubeless mark (the app icon, brand/tubeless-f-tl.svg): a white "T" beside a black "L" in an amber box,
 * on a black rounded square. Paths are the Arial Bold outlines baked by brand/logo/_src/gen7.py (no font needed).
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val t = remember { svgPath("M9.273 48.75V22.786H0V17.5H24.834V22.786H15.582V48.75Z") }
    val box = remember { svgPath("M35.834 10H62.276A4 4 0 0 1 66.276 14V56A4 4 0 0 1 62.276 60H35.834A4 4 0 0 1 31.834 56V14A4 4 0 0 1 35.834 10Z") }
    val l = remember { svgPath("M39.084 48.75V19.244H45.091V43.738H60.026V48.75Z") }
    Canvas(modifier) {
        val s = minOf(size.width, size.height) / 100f
        withTransform({
            translate((size.width - 100f * s) / 2f, (size.height - 100f * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawRoundRect(Color.Black, size = androidx.compose.ui.geometry.Size(100f, 100f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f, 22f))
            withTransform({ translate(15f, 13.0336f); scale(1.05618f, 1.05618f, pivot = Offset.Zero) }) {
                drawPath(t, Color.White)
                drawPath(box, BrandAmber)
                drawPath(l, Color.Black)
            }
        }
    }
}

/**
 * The "Tube"+"less" wordmark (brand/wordmark-f-tl-dark.svg), the same in every theme as the reference logo:
 * a black plate, "Tube" in white, "less" in black on the amber box.
 * [height] is the amber box's height; the black plate around it is 1.4 times as high (205.31:70 overall).
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val tube = remember { svgPath("M19.523 48.75V22.786H10.25V17.5H35.084V22.786H25.832V48.75ZM49.92 48.75V45.361Q48.684 47.173 46.669 48.217Q44.655 49.262 42.417 49.262Q40.136 49.262 38.324 48.26Q36.512 47.258 35.702 45.446Q34.892 43.634 34.892 40.437V26.112H40.882V36.514Q40.882 41.289 41.212 42.366Q41.543 43.442 42.417 44.071Q43.291 44.7 44.634 44.7Q46.168 44.7 47.383 43.858Q48.598 43.016 49.046 41.769Q49.494 40.522 49.494 35.662V26.112H55.484V48.75ZM60.578 48.75V17.5H66.568V28.755Q69.339 25.6 73.134 25.6Q77.269 25.6 79.976 28.595Q82.683 31.59 82.683 37.196Q82.683 42.995 79.923 46.128Q77.163 49.262 73.219 49.262Q71.279 49.262 69.393 48.292Q67.506 47.322 66.142 45.425V48.75ZM66.526 36.941Q66.526 40.458 67.634 42.142Q69.19 44.529 71.769 44.529Q73.752 44.529 75.148 42.835Q76.544 41.14 76.544 37.495Q76.544 33.615 75.137 31.899Q73.731 30.183 71.535 30.183Q69.382 30.183 67.954 31.857Q66.526 33.53 66.526 36.941ZM99.758 41.545 105.727 42.547Q104.576 45.83 102.092 47.546Q99.609 49.262 95.878 49.262Q89.974 49.262 87.139 45.403Q84.9 42.312 84.9 37.601Q84.9 31.974 87.842 28.787Q90.784 25.6 95.282 25.6Q100.334 25.6 103.254 28.936Q106.174 32.272 106.046 39.158H91.04Q91.104 41.822 92.489 43.304Q93.875 44.785 95.942 44.785Q97.349 44.785 98.308 44.018Q99.268 43.25 99.758 41.545ZM100.099 35.491Q100.035 32.891 98.756 31.537Q97.477 30.183 95.644 30.183Q93.683 30.183 92.404 31.612Q91.125 33.04 91.146 35.491Z") }
    val box = remember { svgPath("M117.052 10H191.056A4 4 0 0 1 195.056 14V56A4 4 0 0 1 191.056 60H117.052A4 4 0 0 1 113.052 56V14A4 4 0 0 1 117.052 10Z") }
    val less = remember { svgPath("M120.302 48.75V19H126.004V48.75ZM142.198 41.891 147.88 42.845Q146.785 45.97 144.42 47.603Q142.056 49.237 138.505 49.237Q132.884 49.237 130.185 45.564Q128.054 42.621 128.054 38.137Q128.054 32.779 130.854 29.745Q133.655 26.711 137.937 26.711Q142.746 26.711 145.526 29.887Q148.307 33.063 148.185 39.618H133.898Q133.959 42.155 135.278 43.565Q136.597 44.975 138.566 44.975Q139.905 44.975 140.818 44.245Q141.731 43.514 142.198 41.891ZM142.523 36.128Q142.462 33.652 141.244 32.363Q140.027 31.075 138.282 31.075Q136.415 31.075 135.197 32.434Q133.979 33.794 134 36.128ZM148.692 42.601 154.415 41.729Q154.78 43.393 155.896 44.255Q157.012 45.117 159.021 45.117Q161.233 45.117 162.35 44.306Q163.1 43.738 163.1 42.784Q163.1 42.134 162.694 41.708Q162.268 41.302 160.787 40.957Q153.887 39.435 152.04 38.177Q149.484 36.432 149.484 33.327Q149.484 30.527 151.696 28.619Q153.907 26.711 158.555 26.711Q162.979 26.711 165.13 28.152Q167.281 29.593 168.093 32.414L162.715 33.408Q162.37 32.15 161.406 31.48Q160.442 30.811 158.656 30.811Q156.404 30.811 155.429 31.44Q154.78 31.886 154.78 32.597Q154.78 33.205 155.348 33.631Q156.119 34.2 160.675 35.235Q165.231 36.27 167.037 37.771Q168.823 39.293 168.823 42.013Q168.823 44.975 166.347 47.106Q163.871 49.237 159.021 49.237Q154.618 49.237 152.051 47.451Q149.484 45.665 148.692 42.601ZM169.675 42.601 175.398 41.729Q175.763 43.393 176.88 44.255Q177.996 45.117 180.005 45.117Q182.217 45.117 183.333 44.306Q184.084 43.738 184.084 42.784Q184.084 42.134 183.678 41.708Q183.252 41.302 181.77 40.957Q174.87 39.435 173.024 38.177Q170.467 36.432 170.467 33.327Q170.467 30.527 172.679 28.619Q174.891 26.711 179.538 26.711Q183.962 26.711 186.113 28.152Q188.264 29.593 189.076 32.414L183.698 33.408Q183.353 32.15 182.389 31.48Q181.425 30.811 179.639 30.811Q177.387 30.811 176.413 31.44Q175.763 31.886 175.763 32.597Q175.763 33.205 176.332 33.631Q177.103 34.2 181.659 35.235Q186.214 36.27 188.021 37.771Q189.806 39.293 189.806 42.013Q189.806 44.975 187.331 47.106Q184.855 49.237 180.005 49.237Q175.601 49.237 173.034 47.451Q170.467 45.665 169.675 42.601Z") }
    Canvas(modifier.height(height * (70f / 50f)).width(height * (205.306f / 50f)).semantics { contentDescription = Brand.NAME }) {
        val s = size.height / 70f
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawRoundRect(Color.Black, size = androidx.compose.ui.geometry.Size(205.306f, 70f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f))
            drawPath(tube, Color.White)
            drawPath(box, BrandAmber)
            drawPath(less, Color.Black)
        }
    }
}

@Composable
fun Logo(modifier: Modifier = Modifier) {
    Box(modifier.height(28.dp), contentAlignment = Alignment.Center) { Wordmark(height = 20.dp) }
}

@Composable
// page, depth: the page shown and how deep it is, without the watch sheet over it (so the bar under
// the sheet is already what it will be when the video folds away)
private fun TopBar(app: AppState, onMenu: (() -> Unit)? = null, page: Screen = app.current, depth: Int = app.stack.size) {
    Column {
        val web = webDesk(app)
        // a computer's narrow window (as youtube.com below about 650 px): search is an icon that opens the box over the bar
        var barW by remember { mutableStateOf(2000.dp) }
        val dens = LocalDensity.current
        val narrow = web && barW < 650.dp
        var searching by remember { mutableStateOf(false) }
        LaunchedEffect(narrow) { if (!narrow) searching = false }
        Row(Modifier.fillMaxWidth().height(if (web) 56.dp else 52.dp).onSizeChanged { barW = with(dens) { it.width.toDp() } }
            .padding(start = if (narrow && searching) 4.dp else 14.dp, end = if (web) 16.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
          if (narrow && searching) {
            IconButton(onClick = { searching = false }) { Icon(Sym.ArrowBack.fill, tr("Back")) }
            MastheadSearch(app, Modifier.weight(1f).padding(start = 4.dp), autoFocus = true)
          } else {
            if (onMenu != null) IconButton(onClick = onMenu) { Icon(Sym.Menu.fill, tr("Menu")) }
            if (depth > 1) {
                IconButton(onClick = { app.back() }) { Icon(Sym.ArrowBack.fill, tr("Back")) }
            }
            if (!app.store.settings.value.hideLogo) Logo(Modifier.clickable { app.go(Screen.Home) })
            // a computer: a search box in the middle of the bar, as on YouTube's website (a click opens search)
            if (narrow) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Sym.Search.outline, tr("Search")) }
                MicButton(app, Modifier.padding(horizontal = 4.dp)) { t -> searchFor(app, t) }
            } else if (web) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // the gaps beside the box shrink with the window, so the bell and Sign in always fit
                    MastheadSearch(app, Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = if (barW < 900.dp) 12.dp else 40.dp))
                }
            } else if (!LocalCompact.current && !app.platform.touch && !app.platform.tv) {
                val c = MaterialTheme.colorScheme
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp).height(40.dp)
                            .clip(RoundedCornerShape(20.dp)).border(1.dp, c.outlineVariant, RoundedCornerShape(20.dp))
                            .clickable { app.go(Screen.Search()) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("Search"), Modifier.weight(1f).padding(start = 18.dp), color = c.onSurfaceVariant, maxLines = 1)
                        Box(Modifier.fillMaxHeight().width(64.dp).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                            Icon(Sym.Search.outline, tr("Search"), Modifier.size(22.dp))
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
                // phones: YouTube's app's cast · bell · search (the account and Settings live in the You tab)
                if (LocalCompact.current) PhoneTopActions(app, page)
                else IconButton(onClick = { app.go(Screen.Search()) }) { Icon(Sym.Search.outline, tr("Search")) }
            }
            if (!LocalCompact.current) {
                if (!web && onMenu != null && app.store.settings.value.railCollapsed) IconButton(onClick = { app.go(Screen.Settings) }) { Icon(Sym.Settings.outline, tr("Settings")) }
                // M0 hooks (Hooks.kt): the bell and the account button, empty until S3/S4/S7 fill them
                BellSlot(app)
                AccountSlot(app)
            }
          }
        }
        // every device: Home, Subscriptions, Music, Live and More under the logo, on those pages themselves
        // (not over a phone's Subscriptions: its own tab in the bottom bar, where "Home · Live · More" lit Home)
        if (depth == 1 && page.underHome() && !web && !(LocalCompact.current && page == Screen.Subs)) TopTabs(app)
    }
}

/** A newer version is out: get it now, later, or never for this one. */
@Composable
private fun UpdateDialog(app: AppState, r: app.tubeless.engine.Remote.Release) {
    // the download in the app (Android): null before it starts, then 0..1
    var got by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (got == null) app.update = null },
        title = { Text(tr("Tubeless {v} is out").replace("{v}", r.version)) },
        text = {
            Column {
                Text(tr("You have {v}.").replace("{v}", VERSION) + " " + tr(r.download?.let { "Download it and install it over this one; your library stays." } ?: "Pick the file for your system on the release page."))
                if (r.notes.isNotEmpty()) {
                    Text(tr("What's new"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    // focusable: a TV remote can reach the notes and scroll them
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).focusable()) { NoteLines(r.notes) }
                }
                got?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                    Text(if (p >= 1f) tr("Opening the installer...") else "${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                if (failed) Text(tr("The download failed. Opening the release page instead."), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = got == null, onClick = {
                val url = r.download
                val inApp = url != null && app.platform.installUpdate(url, { got = it }) { ok ->
                    if (ok) app.update = null else { failed = true; app.platform.openExternal(r.page); app.update = null }
                }
                if (inApp) got = 0f else { app.platform.openExternal(url ?: r.page); app.update = null }
            }) { Text(tr(if (r.download != null && (app.platform.name == "android" || r.download.endsWith(".msi"))) "Update now" else "Download")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { app.store.settings.update { it.copy(skipVersion = r.version) }; app.update = null }) { Text(tr("Skip this version")) }
                TextButton(onClick = { app.update = null }) { Text(tr("Later")) }
            }
        },
    )
}

/** The bar above the tabs while a video plays and you look at something else. */
/** The desktop's mini player: the playing video, small, over the page; a click goes back to it. */
@Composable
private fun FloatingPlayer(app: AppState, move: (Offset, IntSize) -> Unit) {
    val v = app.playback.video ?: return
    val st by app.player.state.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    Surface(
        // drag it anywhere by the picture or the title; a click (no drag) still opens the video
        Modifier.width(360.dp).onSizeChanged { size = it }
            .pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); move(d, size) } },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
    ) {
        Column {
            val song = app.playback.song
            val open = { if (song != null) app.go(Screen.NowPlaying) else app.go(Screen.Watch(v.id)) }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).clickable { open() }) {
                if (song != null && !app.playback.withVideo) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SongCover(song, Modifier.fillMaxHeight(), round = 0)
                }
                else if (st.videoId == v.id) app.player.View(Modifier.fillMaxSize())
                else if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.fillMaxSize())
                if (st.durationMs > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(3.dp).background(Red))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp).clickable { open() }) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (song != null) IconButton(onClick = { app.playback.previous() }) { Icon(Sym.SkipPrevious.fill, tr("Previous")) }
                IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                    Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
                }
                if (song != null) IconButton(onClick = { app.playback.skipNext() }) { Icon(Sym.SkipNext.fill, tr("Next")) }
                IconButton(onClick = { app.playback.close() }) { Icon(Sym.Close.fill, tr("Close")) }
            }
        }
    }
}

/** Settings → Wellbeing: time for a break, or today's amount reached */
@Composable
private fun ReminderDialog(app: AppState, msg: String) {
    AlertDialog(
        onDismissRequest = { app.reminder = null },
        icon = { Icon(Sym.Wellbeing.outline, null) },
        title = { Text(tr("Wellbeing")) },
        text = { Text(msg) },
        confirmButton = { TextButton(onClick = { app.reminder = null }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = { app.reminder = null; app.go(Screen.Stats) }) { Text(tr("Watch time")) } },
    )
}

@Composable
private fun MiniPlayer(app: AppState) {
    val v = app.playback.video ?: return
    val song = app.playback.song
    val st by app.player.state.collectAsState()
    // as YouTube's: swipe it up to open the video, sideways to close it (a song: to the next or the last
    // one); it follows the finger and fades as it goes, and let go early it slides back
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val slide = remember(v.id) { Animatable(0f) }
    var wide by remember { mutableStateOf(1f) }
    Column(Modifier.fillMaxWidth().onSizeChanged { wide = it.width.toFloat().coerceAtLeast(1f) }
        .graphicsLayer { translationX = slide.value; alpha = 1f - (kotlin.math.abs(slide.value) / wide).coerceIn(0f, 1f) * 0.9f }
        .pointerInput(v.id) {
            var up = 0f
            detectDragGestures(
                onDragStart = { up = 0f },
                onDrag = { change, d ->
                    change.consume()
                    up += d.y
                    scope.launch { slide.snapTo(slide.value + d.x) }
                },
                onDragCancel = { scope.launch { slide.animateTo(0f, tween(Motion.SHORT)) } },
                onDragEnd = {
                    scope.launch {
                        when {
                            kotlin.math.abs(slide.value) > wide * 0.35f -> {
                                val dir = if (slide.value > 0) 1f else -1f
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
                                slide.animateTo(dir * wide, tween(Motion.SHORT, easing = Motion.emphasizedOut))
                                // a song: sideways is the next or the last one, as in YouTube Music
                                if (app.playback.song != null) {
                                    if (dir < 0) app.playback.skipNext() else app.playback.previous()
                                    slide.snapTo(-dir * wide * 0.5f)
                                    slide.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 420f))
                                } else app.playback.close()
                            }
                            up < -40.dp.toPx() && kotlin.math.abs(slide.value) < 48.dp.toPx() -> {
                                slide.snapTo(0f)
                                if (app.playback.song != null) app.go(Screen.NowPlaying) else app.go(Screen.Watch(v.id))
                            }
                            else -> slide.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 600f))
                        }
                    }
                },
            )
        }
        // a card floating over the tabs, as the video's mini player
        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
        .shadow(6.dp, RoundedCornerShape(14.dp))
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().tvClickable(RoundedCornerShape(0.dp)) { if (song != null) app.go(Screen.NowPlaying) else app.go(Screen.Watch(v.id)) }.padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (song != null) SongCover(song, Modifier.padding(8.dp).size(44.dp).clip(RoundedCornerShape(8.dp)))
            else if (!app.store.settings.value.hideThumbs) Img(v.thumb, Modifier.width(100.dp).aspectRatio(16f / 9f))
            else Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song != null) MiniLyricLine(app, song, v.author)
                else Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = { if (st.playing) app.player.pause() else app.player.play() }) {
                Icon(if (st.playing) Sym.Pause.fill else Sym.PlayArrow.fill, tr(if (st.playing) "Pause" else "Play"))
            }
            if (song != null) IconButton(onClick = { app.playback.skipNext() }) { Icon(Sym.SkipNext.fill, tr("Next")) }
            IconButton(onClick = { app.playback.close() }) { Icon(Sym.Close.fill, tr("Close")) }
        }
        if (st.durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outline)) {
                Box(Modifier.fillMaxWidth((st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)).height(2.dp).background(if (song != null) accent else Red))
            }
        }
    }
}
