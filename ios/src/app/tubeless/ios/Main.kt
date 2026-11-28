package app.tubeless.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import app.tubeless.data.Store
import app.tubeless.engine.Video
import kotlinx.coroutines.launch
import app.tubeless.ui.App
import app.tubeless.ui.AppState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

private var shared: AppState? = null

/**
 * The one AppState, made on first use: by the window (MainViewController) or earlier, when iOS starts
 * the app in the background only to hand it finished downloads (Downloads.kt), with no window at all.
 */
fun appState(): AppState = shared ?: run {
    val platform = IosPlatform()
    val state = AppState(platform, Store(platform.dataDir))
    shared = state
    // the YouTube account (S3): Google sign-in in a WKWebView, cookies in the Keychain
    app.tubeless.ui.installAccounts(state, Keychain(), IosSignIn())
    // Control Center and the lock screen: what plays and its buttons
    MediaControls(state).start()
    // phone settings (background play, picture-in-picture) and what they do when the app leaves the front
    app.tubeless.ui.MobilePrefs.register(state)
    BackgroundAudio(state, platform.player).start()
    DownloadProgress.watch(state)
    val center = NSNotificationCenter.defaultCenter
    // written to disk before iOS may end the app; synced when it comes back
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ -> state.store.flush(); app.tubeless.ui.FeedPlay.stop(state) }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        state.sync.refreshSoon()
    }
    state
}

/**
 * The app, for the Swift side (ios/app/App.swift): one Compose screen in a view controller.
 * [hideBars] is called with true in full screen, when the status bar and the home indicator should go.
 */
fun MainViewController(hideBars: (Boolean) -> Unit): UIViewController {
    val state = appState()
    val platform = state.platform as IosPlatform
    platform.hideBars = hideBars
    // for the simulator check in CI: `-open <video id>` starts on that video
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    args.indexOf("-open").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { id -> state.watch(Video(id, "", "", "", 0)) }
    // `-search <query>` starts on its results, `-shorts` on the Shorts tab (the same simulator check)
    args.indexOf("-search").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { q -> state.go(app.tubeless.ui.Screen.Search(q)) }
    if ("-shorts" in args) state.go(app.tubeless.ui.Screen.Shorts)
    // `-shortsafter <ms>`: Home first, then the Shorts tab as a tap would (the cold "open Shorts" measurement)
    args.indexOf("-shortsafter").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.toLongOrNull()?.let { ms ->
        state.scope.launch { kotlinx.coroutines.delay(ms); state.go(app.tubeless.ui.Screen.Shorts) }
    }
    // `-shortswipe <ms>`: Shorts moves to the next one by itself (the swipe-to-first-frame measurement)
    args.indexOf("-shortswipe").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.toLongOrNull()?.let { app.tubeless.ui.E2E.shortsSwipeMs = it }
    // `-go subs|library|music|settings|downloads|history|mobile`: starts on that page (S10's simulator screenshots)
    args.indexOf("-go").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { p ->
        when (p) {
            "subs" -> state.go(app.tubeless.ui.Screen.Subs)
            "library" -> state.go(app.tubeless.ui.Screen.Library)
            "music" -> state.go(app.tubeless.ui.Screen.Music)
            "settings" -> state.go(app.tubeless.ui.Screen.Settings)
            "downloads" -> state.go(app.tubeless.ui.Screen.Downloads)
            "history" -> state.go(app.tubeless.ui.Screen.History)
            else -> { state.go(app.tubeless.ui.Screen.Settings); state.go(app.tubeless.ui.Screen.SettingsPage(p)) }
        }
    }
    // `-qlog`: what the player shows every 5 s (QUALITY: height, itag, codec, bitrate, dropped frames) on stdout
    if ("-qlog" in args) state.scope.launch {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            val p = state.platform.player.state.value
            println("QUALITY id=${p.videoId} pos=${p.positionMs / 1000}s h=${p.nowHeight} itag=${p.nowItag} codec=${p.nowCodec} fps=${p.nowFps} kbps=${p.nowKbps} dropped=${p.dropped} label=${app.tubeless.engine.Quality.label(p.nowHeight, p.nowFps)} heights=${p.heights}")
        }
    }
    // `-stress`: what a person does over and over (another video, full screen on and off), for the CI's screenshots
    if ("-stress" in args) state.scope.launch {
        kotlinx.coroutines.delay(5_000)
        repeat(2) {
            for (id in listOf("jNQXAC9IVRw", "aqz-KE-bpKQ", "dQw4w9WgXcQ")) {
                state.watch(Video(id, "", "", "", 0)); kotlinx.coroutines.delay(12_000)
                state.setFull(true); kotlinx.coroutines.delay(4_000)
                state.setFull(false); kotlinx.coroutines.delay(3_000)
            }
        }
    }
    return ComposeUIViewController {
        // outside full screen the app stays clear of the status bar and the home indicator (AppFrame, shared with Android)
        app.tubeless.ui.AppFrame(state) {
            // the swipe from the left edge goes back (EdgeBack.kt)
            EdgeBack(state) {
                App(state)
            }
        }
    }.also { platform.root = it }
}
