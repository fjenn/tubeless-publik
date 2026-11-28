package app.tubeless.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import app.tubeless.engine.Accounts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The phone's bottom bar as YouTube's iPhone and Android apps draw it (S10): Home, Shorts, a round
 * "+" in the middle, Subscriptions and You. No pill behind the open tab: its icon turns filled and
 * its label bold, a hairline on top, the bar flat on the bottom edge. Pressing dips the icon and
 * ticks the haptic motor. YouTube's "+" is for uploading; here it opens quick actions (play a copied
 * link, Music, Downloads, History, Settings), so Music, which YouTube's bar doesn't have, stays one
 * tap away.
 *
 * Wired in App.kt's Shell in place of PhoneBar (App.kt belongs to S7; see docs/BUILD-STATUS.md S10).
 */
@Composable
fun PhoneTabBar(app: AppState, root: Screen?) {
    var more by remember { mutableStateOf(false) }
    // on Shorts the bar turns black with white icons, as YouTube's (the Short fills the screen above it)
    // (the page in front, not the tab's root: Settings opened from Shorts has the usual bar, as on YouTube)
    val shorts = app.current == Screen.Shorts
    val base = MaterialTheme.colorScheme
    val scheme = if (!shorts) base else base.copy(background = Color.Black, surface = Color.Black, onSurface = Color.White, outline = Color(0xFF3F3F3F))
    MaterialTheme(colorScheme = scheme) {
    val c = MaterialTheme.colorScheme
    // signed in: the You tab is the account's picture, as YouTube's
    val accounts by Accounts.state.collectAsState()
    val photo = accounts.sessions.firstOrNull { it.key == accounts.current }?.identity?.photo
    Column(Modifier.fillMaxWidth().background(if (shorts) Color.Black else base.surface).windowInsetsPadding(NavigationBarDefaults.windowInsets)) {
        HorizontalDivider(color = c.outline.copy(alpha = 0.6f), thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            // the lit tab: the newest page in the stack that belongs to one (Settings or Downloads opened from
            // Shorts light You, not Shorts); pages of no tab (a channel, a search) keep the one they came from
            val lit = app.stack.asReversed().firstNotNullOfOrNull { tabOf(it) } ?: root?.let(::tabOf)
            PhoneTab(app, tr("Home"), Sym.Home, lit == Screen.Home) { app.go(Screen.Home) }
            if (!app.noShorts) PhoneTab(app, tr("Shorts"), Sym.Bolt, lit == Screen.Shorts) { app.go(Screen.Shorts) }
            PlusTab { more = true }
            PhoneTab(app, tr("Subscriptions"), Sym.Subscriptions, lit == Screen.Subs) { app.go(Screen.Subs) }
            PhoneTab(app, tr("You"), Sym.Person, lit == Screen.Library, photo) { app.go(Screen.Library) }
        }
    }
    }
    if (more) QuickActions(app) { more = false }
}

/** the bottom-bar tab a page belongs to (Home, Shorts, Subs or Library = You), null for pages of no tab */
private fun tabOf(s: Screen): Screen? = when (s) {
    Screen.Home -> Screen.Home
    is Screen.Topic -> if (s.key in TOP_TOPICS) Screen.Home else null
    Screen.Shorts -> Screen.Shorts
    Screen.Subs, Screen.ManageSubs -> Screen.Subs
    Screen.Library, Screen.Music, Screen.Settings, is Screen.SettingsPage, Screen.Downloads, Screen.History, is Screen.Local, Screen.Learned, Screen.Stats -> Screen.Library
    else -> null
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PhoneTab(app: AppState, label: String, icon: Symbol, sel: Boolean, photo: String? = null, go: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val dip by animateFloatAsState(if (pressed) 0.86f else 1f,
        if (pressed) tween(Motion.SHORT) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    Column(
        Modifier.weight(1f).fillMaxHeight().selectable(sel, interactionSource = touch, indication = null, role = Role.Tab) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); go()
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (photo != null) Box(Modifier.size(24.dp).graphicsLayer { scaleX = dip; scaleY = dip }
            .let { if (sel) it.border(1.5.dp, c.onSurface, CircleShape) else it }.padding(if (sel) 2.dp else 0.dp)) { Avatar(photo, 24) }
        else Icon(if (sel) icon.fill else icon.outline, null, Modifier.size(24.dp).graphicsLayer { scaleX = dip; scaleY = dip },
            tint = c.onSurface)
        // Dynamic Type / large text grows the labels only a little, as in YouTube's bar: five words must fit
        val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
        Text(label, fontSize = (10f * minOf(fs, 1.15f) / fs).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.onSurface,
            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 2.dp))
    }
}

/** the round "+" in the middle (YouTube's create button; quick actions here) */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PlusTab(open: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Box(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick); open()
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp).border(1.dp, c.onSurface.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Sym.Add.outline, tr("More"), Modifier.size(26.dp), tint = c.onSurface)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun QuickActions(app: AppState, close: () -> Unit) {
    val clip = LocalClipboardManager.current
    ModalBottomSheet(modifier = Modifier.sheetTop(), onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(tr("Create"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Action(Sym.Link, tr("Play a copied link"), tr("A YouTube link from the clipboard")) {
                close()
                val t = clip.getText()?.text?.trim().orEmpty()
                if (t.contains("youtu")) app.openLink(t) else app.scope.launch { app.snackbar.showSnackbar(tr("No YouTube link copied")) }
            }
            if (!app.store.settings.value.hideMusicTab) Action(Sym.Music, tr("Music"), tr("Songs, albums and your playlists")) { close(); app.go(Screen.Music) }
            if (app.platform.canDownload) Action(Sym.Download, tr("Downloads"), tr("Videos saved on this phone")) { close(); if (app.platform.instant) FullMode.ask(tr("Downloads")) else app.go(Screen.Downloads) }
            Action(Sym.History, tr("History"), tr("What you watched")) { close(); app.go(Screen.History) }
            Action(Sym.Settings, tr("Settings"), null) { close(); app.go(Screen.Settings) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Action(icon: Symbol, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon.outline, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

