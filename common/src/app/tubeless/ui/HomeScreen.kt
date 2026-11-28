package app.tubeless.ui

import app.tubeless.engine.Accounts
import app.tubeless.engine.YtError

import app.tubeless.engine.YouTubeAccount

import app.tubeless.engine.Sys
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.collectAsState
import app.tubeless.engine.InnerTube
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tubeless.engine.Item
import app.tubeless.engine.Video
import app.tubeless.rec.Learn
import app.tubeless.rec.Profiles
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Remembered across visits to the tab so going back to Home doesn't reshuffle it. */
internal object HomeCache {
    val items = mutableStateListOf<Item>()
    var at = 0L
    /** YouTube's own Home was empty: [items] are popular videos instead (one page, nothing more to load) */
    var popular by mutableStateOf(false)
}

/** what the chips over the home feed (drawn with the top tabs, one row) ask the feed to do */
object HomeCtl {
    var show: (Boolean) -> Unit = {}
    var fresh: () -> Unit = {}
    var changed: () -> Unit = {}
}

@Composable
fun HomeScreen(app: AppState) {
    // kids mode: the new videos of the picked channels
    if (app.store.settings.value.kids) { SubsScreen(app); return }
    if (app.store.settings.value.homeOff) {
        FeedOff(tr("The home feed is off"), tr("Search for what you came for, or open your subscriptions.")) {
            Button(onClick = { app.go(Screen.Search()) }) { Text(tr("Search")) }
            TextButton(onClick = { app.go(Screen.Subs) }) { Text(tr("Subscriptions")) }
        }
        return
    }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var errorCode by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load(more: Boolean) {
        if (loading) return
        loading = true; error = null
        try {
            // the popular stand-in is one page: nothing further to ask YouTube's (empty) Home for
            if (more && HomeCache.popular) return
            val skip = if (more) HomeCache.items.map { it.id }.toSet() else emptySet()
            var got: List<Item> = app.rec.home(40, skip)
            // YouTube's Home came back empty (a new visitor): popular videos instead, until it has its own
            val popular = !more && got.isEmpty()
            if (popular) got = app.rec.popular(!app.noShorts)
            if (!more) {
                HomeCache.popular = popular
                if (popular) E2E.log("feed_popular", "items" to got.size)
                HomeCache.items.clear()
                // M3 E2E: Home loaded; signed out it is YouTube's Home for the remembered visitor (FIX1)
                val signed = YouTubeAccount.signedIn
                E2E.log("feed_loaded", "loggedOut" to !signed, "items" to got.size)
                if (!signed) E2E.log("feed_loaded_local", "items" to got.size, "source" to "visitor")
            }
            HomeCache.items.addAll(got.filter { g -> HomeCache.items.none { it.id == g.id } })
            HomeCache.at = Sys.nowMs()
        } catch (e: Exception) {
            errorCode = (e as? YtError)?.http ?: 0
            error = e.message ?: tr("Something went wrong")
        } finally { loading = false }
    }
    LaunchedEffect(Unit) {
        // the popular stand-in is asked again sooner: after a few videos YouTube's own Home may have something
        val age = Sys.nowMs() - HomeCache.at
        if (HomeCache.items.isEmpty() || age > 30 * 60_000 || (HomeCache.popular && age > 3 * 60_000)) load(false)
    }
    // the chip already chosen tapped again, or a Refresh button: to the top, a spinner, a fresh feed from YouTube
    HomeCtl.fresh = {
        if (HomeCache.items.isEmpty()) { app.rec.clearCache(); scope.launch { load(false) } } else app.refreshPage()
        Unit
    }
    HomeCtl.changed = { HomeCache.items.clear(); app.rec.clearCache(); scope.launch { load(false) } }
    when {
        HomeCache.items.isEmpty() && loading -> Skeleton()
        // YouTube answered, but refused (a sign-in it won't take, a bad request): say so, don't claim "offline"
        HomeCache.items.isEmpty() && error != null && errorCode > 0 -> FeedOff(tr("YouTube didn't load your Home"), tr("YouTube answered %1\$s. Try again, or sign out and back in.").replace("%1\$s", errorCode.toString())) {
            Button(onClick = { scope.launch { load(false) } }) { Text(tr("Retry")) }
        }
        HomeCache.items.isEmpty() && error != null -> YouAreOffline(app) { scope.launch { load(false) } }
        // YouTube's own empty Home (a new visitor, history paused): what youtube.com says
        HomeCache.items.isEmpty() -> FeedOff(tr("Try searching to get started"), tr("Start watching videos to help us build a feed of videos you'll love.")) {
            Button(onClick = { app.go(Screen.Search()) }) { Text(tr("Search")) }
            TextButton(onClick = { HomeCtl.fresh() }) { Text(tr("Refresh")) }
        }
        else -> {
          // the next pictures and Shorts get ready in the background once the first page is drawn (ShortsWarm)
          Warmup(app, HomeCache.items.filterIsInstance<Video>())
          HomeFeed(
            if (app.noShorts) HomeCache.items.filter { !(it is Video && it.short) } else HomeCache.items, app, loading = loading,
            onEnd = { load(true) },
            hero = LocalLook.current == 1,
            // a new request to YouTube in the same session (nothing cached), the chosen chip kept
            refresh = { while (loading) kotlinx.coroutines.delay(50); app.rec.clearCache(); load(false) },
            header = {
                if (HomeCache.popular) PopularNote()
                // the chips (All, Discover, the region...) are in the one row of tabs above the page
                if (LocalLook.current == 1) Column { ContinueRow(app) }
                NearbyBanner(app)
            },
          )
        }
    }
}

/** one quiet line over the popular stand-in for an empty Home */
@Composable
private fun PopularNote() {
    Text(
        tr("Popular on YouTube. Watch a few videos and your Home becomes yours."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

fun profileLabel(name: String) = name.ifEmpty { tr("Main") }

/** Imports a profile file; shows what it was added as, or returns null (nothing said) when it isn't one. */
fun importProfile(app: AppState, text: String): String? =
    Profiles.import(app.store, text, tr("Imported"))?.also { n ->
        app.toast(tr("Imported the profile “{p}”").replace("{p}", n))
    }

/** the home feed is built again next time (after a profile changed elsewhere) */
fun clearHome() = HomeCache.items.clear()

/**
 * Taste profiles (rec/Profiles): a chip with the one in use, the others and a new one in its menu.
 * With only the main profile it is just the icon.
 */
@Composable
fun ProfilePicker(app: AppState, changed: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    val parked by app.store.profiles.state.collectAsState()
    val names = remember(s.profile, parked) { Profiles.names(app.store) }
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    Box {
        Chip(false, onClick = { open = true }, label = if (names.size > 1) profileLabel(s.profile) else "", icon = Sym.Psychology.outline)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for (n in names) DropdownMenuItem(
                text = { Text(profileLabel(n)) },
                leadingIcon = { if (n == s.profile) androidx.compose.material3.Icon(Sym.Check.outline, null) },
                onClick = { open = false; if (n != s.profile) { Profiles.switch(app.store, n); changed() } },
            )
            DropdownMenuItem(text = { Text(tr("New profile")) }, leadingIcon = { androidx.compose.material3.Icon(Sym.Add.outline, null) },
                onClick = { open = false; naming = true })
            DropdownMenuItem(text = { Text(tr("Manage profiles")) }, leadingIcon = { androidx.compose.material3.Icon(Sym.Settings.outline, null) },
                onClick = { open = false; app.go(Screen.Learned) })
        }
    }
    if (naming) NameDialog(tr("New profile"), "", { naming = false }) { name ->
        if (name in names) app.toast(tr("That name is already used"))
        else { Profiles.switch(app.store, name); changed() }
    }
}

/** The region the feeds come from, switched from Home: a few common ones, or any two letters in Settings. */
@Composable
fun RegionPicker(app: AppState, changed: () -> Unit) {
    val s by app.store.settings.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    val now = s.region.ifEmpty { app.platform.deviceRegion() }.uppercase()
    Box {
        // a short chip: the globe and the two letters (the full name is in the menu)
        Chip(false, onClick = { open = true }, label = now, icon = Sym.Public.outline)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val common = listOf("US", "GB", "CA", "AU", "IN", "DE", "FR", "ES", "IT", "NL", "TR", "BR", "MX", "JP", "KR", "ID")
            DropdownMenuItem(text = { Text(tr("This device") + " (" + app.platform.deviceRegion().uppercase() + ")") }, onClick = {
                open = false; app.store.settings.update { it.copy(region = "") }; changed()
            })
            for (c in common) DropdownMenuItem(
                text = { Text(Sys.countryName(c, InnerTube.hl) + "  ·  " + c) },
                onClick = { open = false; app.store.settings.update { it.copy(region = c) }; changed() },
            )
            DropdownMenuItem(text = { Text(tr("Other...")) }, onClick = { open = false; app.go(Screen.Settings) })
        }
    }
}

/** What a page shows when its feed is switched off in Settings → Focus. */
@Composable
fun FeedOff(title: String, sub: String, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            actions()
        }
    }
}

/** YouTube can't be reached (Home, Shorts, Search, Subscriptions): nothing streams, but the downloads play (and a retry) */
@Composable
fun YouAreOffline(app: AppState, retry: () -> Unit) {
    // YouTube alone may be out of reach (a proxy, a firewall, YouTube down): not "offline"
    FeedOff(tr("Can't reach YouTube"), tr("Check your connection. Your downloads still play.")) {
        Button(onClick = { app.go(Screen.Downloads) }) { Text(tr("Go to downloads")) }
        TextButton(onClick = retry) { Text(tr("Retry")) }
    }
}

/**
 * First start in the apps (computers, phones, tablets; the website goes straight to Home): one calm page.
 * The language is the device's (no picker). It asks one thing, whether Shorts show, and offers the sign-in;
 * a computer also keeps the file import as a small link. Everything here stays in Settings.
 */
@Composable
fun Welcome(app: AppState) {
    val s by app.store.settings.state.collectAsState()
    fun finish() = app.store.settings.update { it.copy(welcomed = true) }
    fun shorts(on: Boolean) = app.store.settings.update { it.copy(hideShorts = !on) }
    val c = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(50)
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(Modifier.padding(bottom = 36.dp))
            Text(tr("Show YouTube Shorts?"), fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp, textAlign = TextAlign.Center)
            Text(tr("You can change this anytime in Settings."), style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            val on = !s.hideShorts
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShortsChoice(tr("Keep Shorts"), tr("The Shorts tab and Shorts in your feeds"), Sym.Bolt.fill, on, Modifier.weight(1f)) { shorts(true) }
                ShortsChoice(tr("Turn off Shorts"), tr("No Shorts tab, no Shorts anywhere"), Sym.Bolt.fill, !on, Modifier.weight(1f), off = true) { shorts(false) }
            }
            Spacer(Modifier.height(36.dp))
            // where Google's sign-in page can open, signing in is offered here too (as on youtube.com)
            val canSignIn = !app.platform.instant && Accounts.launcher?.available == true
            val dark = ButtonDefaults.buttonColors(containerColor = c.onSurface, contentColor = c.surface)
            if (canSignIn) {
                Button(onClick = { finish(); startSignIn(app) }, shape = pill, colors = dark, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(tr("Sign in to YouTube"), fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { finish() }, shape = pill, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(48.dp)) {
                    Text(tr("Continue without signing in"), color = c.onSurface)
                }
            } else Button(onClick = { finish() }, shape = pill, colors = dark, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(tr("Continue"), fontWeight = FontWeight.SemiBold)
            }
            // a computer: subscriptions from another app's export (phones have it in Settings and You)
            if (!app.platform.touch) TextButton(onClick = { importFile(app) { ok -> if (ok) finish() } }, modifier = Modifier.padding(top = 2.dp)) {
                Text(tr("Import subscriptions from a file"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/** one of the two answers to "Show YouTube Shorts?": a card, the chosen one outlined with a check */
@Composable
private fun ShortsChoice(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, chosen: Boolean,
                         modifier: Modifier, off: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.heightIn(min = 132.dp).clip(shape)
            .background(if (chosen) c.surfaceContainerHigh else c.surfaceContainer)
            .border(if (chosen) 2.dp else 1.dp, if (chosen) c.onSurface else c.outlineVariant, shape)
            .selectable(chosen, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(if (off) c.surfaceContainerHighest else Red), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (off) c.onSurfaceVariant else Color.White)
            // off: the bolt struck through
            if (off) Box(Modifier.size(width = 24.dp, height = 2.dp).rotate(-45f).background(c.onSurfaceVariant, RoundedCornerShape(1.dp)))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
