package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * The phone's chrome as YouTube's iPhone and Android apps draw it (MOB): the top bar's cast · bell ·
 * search, and the Explore sheet behind the compass chip at the start of Home's chips. Shared by every
 * touch platform; nothing here depends on the operating system.
 */

/** the right end of the phone's top bar: cast (AirPlay on iOS), notifications, search; the You tab: cast, search, settings */
@Composable
fun PhoneTopActions(app: AppState, page: Screen) {
    CastButton(app, tint = MaterialTheme.colorScheme.onSurface)
    val you = page == Screen.Library
    if (!you) BellSlot(app)
    IconButton(onClick = { app.go(Screen.Search()) }) { Icon(Sym.Search.outline, tr("Search")) }
    if (you) IconButton(onClick = { app.go(Screen.Settings) }) { Icon(Sym.Settings.outline, tr("Settings")) }
}

/** the icon of a topic in the Explore sheet */
private fun topicIcon(k: String): ImageVector = when (k) {
    "Music" -> Sym.Music.outline
    "Live" -> Sym.LiveTv.outline
    "News" -> Sym.News.outline
    "Sports" -> Sym.Trophy.outline
    "Learning" -> Sym.School.outline
    "Gaming" -> Sym.Gaming.outline
    "Fashion & beauty" -> Sym.Apparel.outline
    else -> Sym.Explore.outline
}

/**
 * YouTube's Explore, opened from the compass chip: the topic pages (Music, Live, Gaming, News, ...),
 * then where Home's videos come from (region, taste profile).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSheet(app: AppState, close: () -> Unit) {
    ModalBottomSheet(modifier = Modifier.sheetTop(), onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(tr("Explore"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            for (k in TOPICS.keys) Row(
                Modifier.fillMaxWidth().clickable { close(); app.go(Screen.Topic(k)) }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(topicIcon(k), null)
                Text(tr(k), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RegionPicker(app) { HomeCtl.changed() }
                ProfilePicker(app) { HomeCtl.changed() }
            }
        }
    }
}

/**
 * The window around the app on a phone or tablet, the same on iOS and Android: outside full screen
 * the app stays clear of the status bar and the home indicator / navigation bar, and the strip
 * behind them takes the page's colour (black on Shorts, as YouTube's), with the clock and battery in
 * the colour that reads on it.
 */
@Composable
fun AppFrame(app: AppState, content: @Composable BoxScope.() -> Unit) {
    val settings by app.store.settings.state.collectAsState()
    val dark = when (settings.dark) { 1 -> false; 2, 3 -> true; else -> isSystemInDarkTheme() }
    val shorts = app.current is Screen.Shorts && !app.fullscreen
    val edge = when { shorts || settings.dark == 3 -> Color.Black; dark -> Color(0xFF0F0F0F); else -> Color.White }
    // the system's own look while the theme follows it (iOS: telling it otherwise would also turn the theme over)
    val strip = when { shorts -> true; settings.dark == 0 -> null; else -> dark }
    LaunchedEffect(strip) { app.platform.statusBarDark(strip) }
    Box(Modifier.fillMaxSize().background(edge).let { if (app.fullscreen || app.pip) it else it.windowInsetsPadding(WindowInsets.safeDrawing) }, content = content)
}
