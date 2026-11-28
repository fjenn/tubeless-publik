package app.tubeless.ui

import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import app.tubeless.data.Meta
import app.tubeless.engine.Accounts
import app.tubeless.engine.YouTube
import app.tubeless.engine.YouTubeAccount
import app.tubeless.engine.AccountFeed
import app.tubeless.engine.AccountPlaylist
import app.tubeless.engine.HistoryControls
import app.tubeless.engine.Item
import app.tubeless.engine.Shelf
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tubeless.data.Transfer
import app.tubeless.data.ImportResult
import app.tubeless.data.SubGroup
import androidx.compose.material3.HorizontalDivider
import app.tubeless.engine.Video
import app.tubeless.engine.Sys
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- subscriptions
@Composable
fun SubsScreen(app: AppState) {
    val set by app.store.settings.state.collectAsState()
    // signed in: the account's own Subscriptions page (Latest + "Most relevant"); the local feed when that
    // fails, in kids mode, with the feed off, or on one of this device's groups
    var failed by remember(app.account) { mutableStateOf(false) }
    if (app.feedSource.signedIn && !failed && !set.kids && !set.subsFeedOff && set.subsGroup.isEmpty() && !(set.subsHideAll && app.store.lib.value.groups.isNotEmpty())) {
        AccountSubs(app) { failed = true }
        return
    }
    LocalSubsScreen(app)
}

/** the account's Subscriptions feed, page by page, with the "Most relevant" row above it */
@Composable
private fun AccountSubs(app: AppState, fail: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember(app.account) { mutableStateOf<List<Item>>(emptyList()) }
    var shelves by remember(app.account) { mutableStateOf<List<Shelf>>(emptyList()) }
    var next by remember(app.account) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    suspend fun load(more: Boolean) {
        if (loading || (more && next == null)) return
        loading = true
        try {
            val f = runCatching { if (more) next?.let { YouTubeAccount.more(it) } else YouTubeAccount.subscriptions() }.getOrNull()
            if (f == null || f.loggedOut) { if (!more) fail(); return }
            items = if (more) items + f.items else f.items
            if (!more) shelves = f.shelves
            next = f.next
        } finally { loading = false }
    }
    LaunchedEffect(app.account) { load(false) }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    val shown = items.filter { it !is Video || ((!it.short || !app.noShorts) && words.all { w -> w in it.title.lowercase() || w in it.author.lowercase() }) }
    ItemGrid(
        shown, app, loading = loading, onEnd = { load(true) }, refresh = { load(false) },
        header = {
            Column {
                if (lib.groups.isNotEmpty()) GroupTabs(app, lib.groups, null, false)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Latest"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    PillButton(tr("Manage"), Sym.Settings.outline, height = 36.dp) { app.go(Screen.ManageSubs) }
                    PillButton(tr("Refresh"), Sym.Replay.outline, height = 36.dp, modifier = Modifier.padding(start = 8.dp)) { app.refreshPage() }
                }
                SearchField(query, { query = it }, tr("Search this feed"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                for (sh in shelves.filter { s -> s.items.any { it is Video && !it.short } }) {
                    ShelfTitle(sh.title)
                    val vids = sh.items.filterIsInstance<Video>()
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(vids, key = { "rel" + it.id }) { v -> Box(Modifier.width(280.dp)) { VideoCard(v, app, vids) } }
                    }
                }
            }
        },
    )
}

@Composable
private fun LocalSubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(app.feed.cached().filter { it.channelId in lib.subs }) }
    var loading by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    suspend fun load(force: Boolean) {
        loading = true
        try { items = app.feed.all(force); unreachable = app.feed.unreachable } finally { loading = false }
    }
    LaunchedEffect(lib.subs.size) { if (!app.store.settings.value.subsFeedOff) load(false) }
    // groups: one tab each; "All" first unless it's switched off
    val set by app.store.settings.state.collectAsState()
    val hideAll = set.subsHideAll && lib.groups.isNotEmpty()
    val group = lib.groups.firstOrNull { it.name == set.subsGroup } ?: if (hideAll) lib.groups.first() else null
    val shown: Set<String> = group?.channels?.filter { it in lib.subs }?.toSet() ?: lib.subs.keys
    val tabs: @Composable () -> Unit = { GroupTabs(app, lib.groups, group?.name, hideAll) }
    // typed words narrow the feed as you type (title or channel)
    var query by remember { mutableStateOf("") }
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (lib.subs.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // signed out, as YouTube's app: sign in first (the account's channels come with it), importing a file second
            if (canSignInHere(app) && !app.feedSource.signedIn) {
                Text(tr("Don't miss new videos"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(tr("Sign in to see updates from your favorite YouTube channels"), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { startSignIn(app) }, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Sign in")) }
                OutlinedButton(onClick = { importFile(app) }, modifier = Modifier.padding(top = 8.dp)) { Text(tr("Import subscriptions")) }
            } else {
            Text(tr("Channels you subscribe to show up here. Subscriptions are kept on this device - no account needed."),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { importFile(app) }, modifier = Modifier.padding(top = 16.dp)) { Text(tr("Import subscriptions")) }
            Text(tr("From YouTube (Google Takeout), NewPipe, FreeTube, Invidious, Tubeless or any OPML file"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    // feed switched off: just the channels, each opens its own page
    if (app.store.settings.value.subsFeedOff) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(tr("The subscription feed is off. Open a channel to see its videos."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            item {
                TextButton(onClick = { app.go(Screen.ManageSubs) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(tr("Manage subscriptions")) }
            }
            if (lib.groups.isNotEmpty()) item { tabs() }
            items(lib.subs.entries.filter { it.key in shown }.sortedBy { it.value.name.lowercase() }.toList(), key = { it.key }) { (cid, s) ->
                Row(
                    Modifier.fillMaxWidth().clickable { app.go(Screen.Channel(cid, s.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(s.thumb, 40)
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
        return
    }
    // YouTube out of reach: say so (Retry, Go to downloads), not an empty feed
    if (unreachable && !loading && items.isEmpty()) { YouAreOffline(app) { scope.launch { load(true) } }; return }
    val base = items.filter { (!it.short || !app.noShorts) && it.channelId in shown }
    // a group: its own short feed (unwatched, the last 7 days, every channel's newest first, at most 20)
    val feed = if (group != null) groupFeed(base, lib.hist.mapTo(HashSet()) { it.id }, app.store.settings.value.groupDays) else base
    ItemGrid(
        feed.filter { v -> words.all { w -> w in v.title.lowercase() || w in v.author.lowercase() } }, app, loading = loading,
        refresh = { load(true) },
        header = {
            Column {
                if (lib.groups.isNotEmpty()) tabs()
                // above the search, where they're seen at once
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (group != null) Text(
                        tr(if (app.store.settings.value.groupDays >= 30) "Each channel's newest video from the last 30 days, if you haven't watched it" else "Each channel's newest video from the last 7 days, if you haven't watched it"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp),
                    ) else Spacer(Modifier.weight(1f))
                    PillButton(tr("Manage"), Sym.Settings.outline, height = 36.dp) { app.go(Screen.ManageSubs) }
                    PillButton(tr("Refresh"), Sym.Replay.outline, height = 36.dp, modifier = Modifier.padding(start = 8.dp)) { app.refreshPage() }
                }
                SearchField(query, { query = it }, tr("Search this feed"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.subs.entries.filter { it.key in shown }.sortedByDescending { it.value.at }.toList(), key = { it.key }) { (cid, s) ->
                        Column(Modifier.width(64.dp).tvClickable { app.go(Screen.Channel(cid, s.name)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(s.thumb, 56)
                            Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
    )
}

/**
 * A group's feed (asked for by PocketTube users): one video per channel, its newest, when it came
 * out in the last 7 days and you haven't watched it yet; members-only videos left out. So it ends
 * after a screen or so instead of scrolling on, and a busy channel can't fill it.
 */
private fun groupFeed(items: List<Video>, watched: Set<String>, days: Int): List<Video> {
    val since = Sys.nowMs() / 1000 - days.coerceAtLeast(1) * 86400L
    return items.filter { !it.members }.distinctBy { it.id }.groupBy { it.channelId }.values
        .map { l -> l.maxBy { it.publishedAt } }
        .filter { it.id !in watched && (it.publishedAt == 0L || it.publishedAt >= since) }
        .sortedByDescending { it.publishedAt }
}

/** The groups as tabs above the subscriptions: All (unless switched off), then each group. */
@Composable
private fun GroupTabs(app: AppState, groups: List<SubGroup>, selected: String?, hideAll: Boolean) {
    fun pick(name: String) = app.store.settings.update { it.copy(subsGroup = name) }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hideAll) item { Chip(selected == null, onClick = { pick("") }, label = tr("All")) }
        items(groups, key = { "g" + it.name }) { g ->
            Chip(selected == g.name, onClick = { pick(g.name) }, label = g.name)
        }
    }
}

/** Every subscribed channel, to remove one or all of them, and the groups they are sorted into. */
@Composable
fun ManageSubsScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.fillSubInfo() }
    val all = lib.subs.entries.sortedBy { it.value.name.lowercase() }.toList()
    // the channel list narrows as you type, to find the ones to put in a group
    var query by remember { mutableStateOf("") }
    val subs = if (query.isBlank()) all else all.filter { (cid, s) -> s.name.ifEmpty { cid }.contains(query.trim(), ignoreCase = true) }
    val set by app.store.settings.state.collectAsState()
    var newGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var sorting by remember { mutableStateOf<String?>(null) }    // the channel whose groups are being picked
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Groups"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { newGroup = true }) { Text(tr("New group")) }
            }
            if (lib.groups.isEmpty()) Text(
                tr("Sort channels into groups and Subscriptions gets a tab for each. A PocketTube backup brings its groups along (You → Import and export)."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        items(lib.groups, key = { "g" + it.name }) { g ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(tr("{n} channels").replace("{n}", "${g.channels.count { it in lib.subs }}"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { renaming = g.name }) { Text(tr("Rename")) }
                IconButton(onClick = { deleting = g.name }) { Icon(Sym.Close.outline, tr("Delete")) }
            }
        }
        // the same switch as in Settings, here where the groups are made
        if (lib.groups.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().clickable { app.store.settings.update { it.copy(subsHideAll = !set.subsHideAll) } }.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Only my groups in Subscriptions"), style = MaterialTheme.typography.bodyLarge)
                    Text(tr("With subscription groups: no \"All\" tab, just the groups"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(set.subsHideAll, { b -> app.store.settings.update { it.copy(subsHideAll = b) } })
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline) }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("{n} channels").replace("{n}", "${all.size}"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (all.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text(tr("Unsubscribe from all"), color = MaterialTheme.colorScheme.error) }
            }
        }
        if (all.size > 5) item {
            SearchField(query, { query = it }, tr("Search your subscriptions"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        if (subs.isEmpty()) item {
            Text(tr(if (all.isEmpty()) "No subscriptions" else "Nothing here"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        items(subs, key = { it.key }) { (cid, s) ->
            Row(
                Modifier.fillMaxWidth().clickable { app.go(Screen.Channel(cid, s.name)) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(s.thumb, 40)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(s.name.ifEmpty { cid }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val inGroups = lib.groups.filter { cid in it.channels }.joinToString(", ") { it.name }
                    if (inGroups.isNotEmpty()) Text(inGroups, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { sorting = cid }) { Icon(Sym.LibraryAdd.outline, tr("Groups")) }
                IconButton(onClick = { app.store.subscribe(cid, s.name, s.thumb, false) }) {
                    Icon(Sym.Close.outline, tr("Unsubscribe"))
                }
            }
        }
    }
    if (newGroup) NameDialog(tr("New group"), "", { newGroup = false }) { app.store.newGroup(it); newGroup = false }
    renaming?.let { old -> NameDialog(tr("Rename"), old, { renaming = null }) { app.store.renameGroup(old, it); renaming = null } }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Delete the group \"{g}\"?").replace("{g}", name)) },
            text = { Text(tr("The channels stay subscribed.")) },
            confirmButton = { TextButton(onClick = { app.store.deleteGroup(name); deleting = null }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancel")) } },
        )
    }
    sorting?.let { cid -> GroupPicker(app, cid) { sorting = null } }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Unsubscribe from all {n} channels?").replace("{n}", "${all.size}")) },
        text = { Text(tr("Your history and playlists stay. You can import subscriptions again at any time.")) },
        confirmButton = {
            TextButton(onClick = { app.store.unsubscribeAll(); confirm = false }) { Text(tr("Unsubscribe from all"), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

// ---------------------------------------------------------------- library
/**
 * The You tab, laid out as YouTube's app: the account (or Sign in), History as a row with View all,
 * Playlists as a row (Watch later, Liked videos, yours, the account's) with + and View all, then
 * Downloads, the recap and the rest as plain rows. The same page on every device.
 */
@Composable
fun LibraryScreen(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    var newPl by remember { mutableStateOf(false) }
    val saved by app.offline.saved.collectAsState()
    val busy = app.offline.progress.size
    fun videos(n: Int) = if (n == 1) tr("1 video") else tr("{n} videos").replace("{n}", "$n")
    var accountLists by remember(app.account) { mutableStateOf(if (app.feedSource.signedIn) YouTubeAccount.lastPlaylists else emptyList()) }
    // signed in: the account's own History row and its Watch later / Liked videos (count, picture), as YouTube's You page
    var accountHist by remember(app.account) { mutableStateOf<List<Video>?>(null) }
    if (app.feedSource.signedIn) LaunchedEffect(app.account) {
        accountLists = runCatching { YouTubeAccount.playlists() }.getOrNull().orEmpty()
        accountHist = runCatching { YouTubeAccount.history()?.takeIf { !it.loggedOut }?.everything?.filter { !it.short } }.getOrNull()
    }
    val system = if (app.feedSource.signedIn) YouTubeAccount.systemLists.takeIf { accountLists.isNotEmpty() || it.isNotEmpty() } else null
    val hist: List<Video> = accountHist ?: lib.hist.map { it.video() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { YouHeader(app) }
        item {
            YouShelfTitle(tr("History"), onAll = { app.go(Screen.History) })
            if (hist.isEmpty()) Text(tr("Videos you watch show up here"), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            // a new row when the account's History replaces the local one: the old row's scroll position followed
            // its first item by key and opened the account's row scrolled, its newest videos off to the left
            else androidx.compose.runtime.key(accountHist == null) { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(hist.take(20), key = { it.id }) { v ->
                    Column(Modifier.width(160.dp).tvClickable { app.watch(v) }) {
                        if (!app.store.settings.value.hideThumbs) Thumb(v, app, Modifier.fillMaxWidth(), rounded = 8)
                        Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        if (v.author.isNotEmpty()) Text(v.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }
        }
        item {
            YouShelfTitle(tr("Playlists"), onAdd = { newPl = true }, onAll = null)
            data class Tile(val title: String, val sub: String, val thumb: String?, val open: () -> Unit)
            val tiles = buildList {
                val wl = system?.get("WL"); val ll = system?.get("LL")
                add(Tile(tr("Watch later"), wl?.countText?.ifEmpty { null } ?: videos(lib.wl.size), wl?.thumb?.ifEmpty { null } ?: lib.wl.firstOrNull()?.video()?.thumb) { app.go(Screen.Local("wl")) })
                add(Tile(tr("Liked videos"), ll?.countText?.ifEmpty { null } ?: videos(lib.likes.size), ll?.thumb?.ifEmpty { null } ?: lib.likes.firstOrNull()?.video()?.thumb) { app.go(Screen.Local("likes")) })
                for ((id, p) in lib.playlists.entries.sortedByDescending { it.value.updated })
                    add(Tile(p.title, videos(p.items.size), p.items.firstOrNull()?.video()?.thumb) { app.go(Screen.Local(id)) })
                // (a playlist deleted a moment ago is left out at once: YouTubeAccount.forgetPlaylist)
                for (p in accountLists.filter { it.id !in lib.playlists && !YouTubeAccount.isDeleted(it.id) })
                    add(Tile(p.title, p.countText, p.thumb.ifEmpty { null }) { app.go(Screen.Playlist(p.id, p.title)) })
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tiles) { t ->
                    Column(Modifier.width(160.dp).tvClickable(onClick = t.open)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            if (t.thumb != null && !app.store.settings.value.hideThumbs) Img(t.thumb, Modifier.fillMaxSize())
                            else Icon(Sym.PlaylistPlay.outline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        item { androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 4.dp)) }
        if (app.platform.canDownload) item {
            LibRow(Sym.Download.outline, tr("Downloads"), videos(saved.items.size) + if (busy > 0) " (+$busy)" else "") { if (app.platform.instant) FullMode.ask(tr("Downloads")) else app.go(Screen.Downloads) }
        }
        item { LibRow(Sym.BarChart.outline, tr("Watch time"), "") { app.go(Screen.Stats) } }
        item { LibRow(Sym.Psychology.outline, tr("What Tubeless learned about you"), "") { app.go(Screen.Learned) } }
        item { LibRow(Sym.SwapVert.outline, tr("Import and export"), "") { app.go(Screen.SettingsPage("data")) } }
        item { LibRow(Sym.Settings.outline, tr("Settings"), "") { app.go(Screen.Settings) } }
    }
    if (newPl) NameDialog(tr("New playlist"), "", { newPl = false }) { app.store.newPlaylist(it, null) }
}

/** a row's title on the You tab, as YouTube's: bold, with + and a "View all" pill on the right */
@Composable
private fun YouShelfTitle(text: String, onAdd: (() -> Unit)? = null, onAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onAdd != null) IconButton(onClick = onAdd, modifier = Modifier.size(36.dp)) { Icon(Sym.Add.outline, tr("New playlist")) }
        if (onAll != null) Box(
            Modifier.padding(start = 4.dp).height(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .clickable(onClick = onAll).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("View all"), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun LibRow(icon: ImageVector, label: String, count: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Text(label, Modifier.weight(1f).padding(start = 20.dp), style = MaterialTheme.typography.titleSmall)
        Text(count, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Videos saved with only their id (a playlist or history imported from Google Takeout, Invidious and the
 *  like) have no title or channel to show (#71): look those up once the list is open, a few at a time. */
@Composable
private fun FillMissingTitles(app: AppState, list: String, items: List<Meta>) {
    // again when the list changes (a sync from your server brings items after the list is open)
    val missing = items.filter { it.title.isEmpty() }.map { it.id }.distinct().take(200)
    LaunchedEffect(list, missing) {
        for (part in missing.chunked(4)) coroutineScope {
            for (id in part) launch {
                val d = try { YouTube.details(id) } catch (_: Exception) { null }
                if (d != null && d.title.isNotEmpty()) app.store.fillMeta(id, d.title, d.author, d.channelId)
                else oembed(id)?.let { (t, a) -> app.store.fillMeta(id, t, a, d?.channelId.orEmpty()) }
            }
        }
    }
}

/** title and channel name from YouTube's oEmbed (a small public JSON), when the watch page gives none */
private suspend fun oembed(id: String): Pair<String, String>? = try {
    val j = kotlinx.serialization.json.Json.parseToJsonElement(
        app.tubeless.engine.Net.get("https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id"),
    ) as kotlinx.serialization.json.JsonObject
    val t = (j["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val a = (j["author_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    if (t.isEmpty()) null else t to a
} catch (_: Exception) {
    null
}

@Composable
fun HistoryScreen(app: AppState) {
    // signed in: the account's watch history (every device), searchable, by day; this device's when that fails
    var failed by remember(app.account) { mutableStateOf(false) }
    if (app.feedSource.signedIn && !failed) { AccountHistory(app) { failed = true }; return }
    LocalHistory(app)
}

/** a day's row of the account's history, as YouTube groups it */
private class Day(val title: String, val videos: List<Video>)

@Composable
private fun AccountHistory(app: AppState, fail: () -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var days by remember(app.account) { mutableStateOf<List<Day>>(emptyList()) }
    var next by remember(app.account) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember(app.account) { mutableStateOf(false) }
    var controls by remember(app.account) { mutableStateOf<HistoryControls?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var removed by remember(app.account) { mutableStateOf(setOf<String>()) }
    fun daysOf(f: AccountFeed): List<Day> {
        val out = f.shelves.map { s -> Day(s.title, s.items.filterIsInstance<Video>()) }.toMutableList()
        val rest = f.videos
        if (rest.isNotEmpty()) out += Day("", rest)
        // an item YouTube says was partly watched gives this device its resume point (when it has none)
        for (d in out) for (v in d.videos) f.actions[v.id]?.resumePercent?.let { p ->
            if (v.lengthSec > 0 && p in 3..95) app.store.takeResume(v, v.lengthSec * p / 100L, at = 1)
        }
        return out
    }
    suspend fun load(more: Boolean) {
        if (loading || (more && next == null)) return
        loading = true
        try {
            val f = runCatching {
                if (more) next?.let { YouTubeAccount.more(it) } else YouTubeAccount.history(q)
            }.getOrNull()
            if (f == null || f.loggedOut) { if (!more && q.isBlank()) fail(); return }
            val got = daysOf(f)
            // a page that starts with the day the last one ended on continues it
            days = if (!more) got else {
                val last = days.lastOrNull()
                if (last != null && got.isNotEmpty() && (got[0].title == last.title || got[0].title.isEmpty()))
                    days.dropLast(1) + Day(last.title, last.videos + got[0].videos) + got.drop(1)
                else days + got
            }
            next = f.next
            loaded = true
        } finally { loading = false }
    }
    LaunchedEffect(app.account, q) {
        if (q.isNotBlank()) kotlinx.coroutines.delay(500)
        load(false)
    }
    LaunchedEffect(app.account) { controls = runCatching { YouTubeAccount.historyControls() }.getOrNull() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(q, { q = it }, tr("Search watch history"), Modifier.weight(1f).padding(vertical = 6.dp))
            PillButton(tr("Clear all"), height = 40.dp, modifier = Modifier.padding(start = 8.dp)) { confirm = true }
            controls?.let { c ->
                if (c.pause != null || c.resume != null) PillButton(tr(if (c.paused) "Turn on watch history" else "Pause watch history"), height = 40.dp,
                    modifier = Modifier.padding(start = 8.dp)) {
                    scope.launch {
                        val ok = YouTubeAccount.setHistoryOn(c.paused)
                        app.toast(tr(if (!ok) "Couldn't reach YouTube" else if (c.paused) "Watch history is on" else "Watch history paused"))
                        controls = runCatching { YouTubeAccount.historyControls() }.getOrNull() ?: controls
                    }
                }
            }
        }
        val shown = days.map { d -> Day(d.title, d.videos.filter { it.id !in removed && (!it.short || !app.noShorts) }) }.filter { it.videos.isNotEmpty() }
        if (shown.isEmpty()) { if (loading || !loaded) Skeleton(rows = true) else Empty(tr("Nothing here")) }
        else LazyColumn(Modifier.fillMaxSize()) {
            for ((di, d) in shown.withIndex()) {
                if (d.title.isNotEmpty()) item(key = "day$di") { SectionTitle(d.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                items(d.videos, key = { "$di:" + it.id }) { v ->
                    VideoRow(v, app, extra = listOf(tr("Remove from watch history") to {
                        removed = removed + v.id
                        app.store.removeFromHistory(v.id)
                    }))
                }
            }
            if (next != null) item(key = "more") { LaunchedEffect(next) { load(true) }; Busy() }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Clear all watch history?")) },
        text = { Text(tr("Your YouTube watch history is cleared on every device, and so are the resume points here. This can't be undone.")) },
        confirmButton = { TextButton(onClick = { app.store.clearHistory(); days = emptyList(); next = null; confirm = false }) { Text(tr("Clear")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun LocalHistory(app: AppState) {
    val lib by app.store.lib.state.collectAsState()
    FillMissingTitles(app, "hist", lib.hist)
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val list = lib.hist.filter { q.isBlank() || (it.title + " " + it.author).contains(q, ignoreCase = true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(q, { q = it }, tr("Search your history"), Modifier.weight(1f).padding(vertical = 6.dp))
            PillButton(tr("Clear all"), height = 40.dp, modifier = Modifier.padding(start = 8.dp)) { confirm = true }
        }
        // grouped by when it was watched, as YouTube's History (M3): Today, Yesterday, This week, This month, Earlier
        val nowS = Sys.nowMs() / 1000
        val dayStart = nowS - Sys.hour() * 3600L - nowS % 3600
        fun group(at: Long) = when {
            at <= 0L -> "Earlier"
            at >= dayStart -> "Today"
            at >= dayStart - 86_400 -> "Yesterday"
            at >= dayStart - 6 * 86_400 -> "This week"
            at >= dayStart - 29 * 86_400 -> "This month"
            else -> "Earlier"
        }
        if (list.isEmpty()) Empty(tr("Nothing here")) else LazyColumn(Modifier.fillMaxSize()) {
            for ((g, ms) in list.groupBy { group(it.at) }) {
                item(key = "day-$g") {
                    Text(tr(g), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(ms, key = { it.id }) { m ->
                    VideoRow(m.video(), app, extra = listOf(tr("Remove from history") to { app.store.removeFromHistory(m.id) }))
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(tr("Clear all watch history?")) },
        text = { Text(tr("Resume points are cleared too. This can't be undone.")) },
        confirmButton = { TextButton(onClick = { app.store.clearHistory(); confirm = false }) { Text(tr("Clear")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Cancel")) } },
    )
}

/** Watch later, liked videos or one of your playlists. */
@Composable
fun LocalListScreen(app: AppState, key: String) {
    // signed in: Watch later and Liked are the account's (any device); this device's when that fails
    var failed by remember(app.account, key) { mutableStateOf(false) }
    if ((key == "wl" || key == "likes") && app.feedSource.signedIn && !failed) { AccountList(app, key) { failed = true }; return }
    DeviceList(app, key)
}

@Composable
private fun AccountList(app: AppState, key: String, fail: () -> Unit) {
    val kind = if (key == "wl") FeedKind.WATCH_LATER else FeedKind.LIKED
    var videos by remember(app.account, key) { mutableStateOf<List<Video>>(emptyList()) }
    var next by remember(app.account, key) { mutableStateOf<String?>(null) }
    var loaded by remember(app.account, key) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    suspend fun load(more: Boolean) {
        if (loading || (more && next == null)) return
        loading = true
        try {
            val p = if (more) next?.let { app.feedSource.more(kind, it) } else app.feedSource.page(kind)
            if (p == null) { if (!more) fail(); return }
            videos = (if (more) videos + p.items else p.items).distinctBy { it.id }
            next = p.next
            loaded = true
        } finally { loading = false }
    }
    LaunchedEffect(app.account, key) {
        // the list's full count ("1,234 videos") comes with the playlists page
        if (app.feedSource.signedIn && YouTubeAccount.systemLists.isEmpty()) runCatching { YouTubeAccount.playlists() }
        load(false)
    }
    val title = tr(if (key == "wl") "Watch later" else "Liked videos")
    val header: @Composable (Boolean) -> Unit = { panel ->
        ListHeader(
            cover = if (app.store.settings.value.hideThumbs) "" else videos.firstOrNull()?.thumb ?: "",
            title = title, line = YouTubeAccount.systemLists[if (key == "wl") "WL" else "LL"]?.countText?.takeIf { it.isNotEmpty() && app.feedSource.signedIn }
                ?: tr("{n} videos").replace("{n}", "${videos.size}" + if (next != null) "+" else ""), enabled = videos.isNotEmpty(),
            play = { videos.firstOrNull()?.let { app.watch(it, videos.drop(1)) } },
            shuffle = { videos.shuffled().let { s -> s.firstOrNull()?.let { app.watch(it, s.drop(1)) } } },
            panel = panel,
        )
    }
    SplitList({ header(true) }) { inList -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = if (inList) 0.dp else 8.dp)) {
        if (inList) item { header(false) }
        if (videos.isEmpty()) item { if (loading || !loaded) Busy() else Empty(tr("Nothing here")) }
        items(videos.withIndex().toList(), key = { it.value.id }) { (i, v) ->
            VideoRow(v, app, extra = listOf(tr("Remove from this list") to {
                videos = videos.filter { it.id != v.id }
                app.store.setInList(key, v, false)
            })) { app.watch(v, videos.drop(i + 1)) }
        }
        if (next != null) item(key = "more") { LaunchedEffect(next) { load(true) }; Busy() }
    } }
}

@Composable
private fun DeviceList(app: AppState, key: String) {
    val lib by app.store.lib.state.collectAsState()
    val pl = lib.playlists[key]
    val (title, items) = when (key) {
        "wl" -> tr("Watch later") to lib.wl
        "likes" -> tr("Liked videos") to lib.likes
        else -> (pl?.title ?: "") to (pl?.items ?: emptyList())
    }
    FillMissingTitles(app, key, items)
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val videos = items.map { it.video() }
    val header: @Composable (Boolean) -> Unit = { panel ->
        ListHeader(
            cover = if (app.store.settings.value.hideThumbs) "" else videos.firstOrNull()?.thumb ?: "",
            title = title, line = tr("{n} videos").replace("{n}", "${items.size}"), enabled = videos.isNotEmpty(),
            play = { videos.firstOrNull()?.let { app.watch(it, videos.drop(1)) } },
            shuffle = { videos.shuffled().let { s -> s.firstOrNull()?.let { app.watch(it, s.drop(1)) } } },
            panel = panel,
        ) {
            if (pl != null) {
                PillButton(tr("Rename"), height = 36.dp) { rename = true }
                PillButton(tr("Delete"), height = 36.dp) { delete = true }
            }
        }
    }
    SplitList({ header(true) }) { inList -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = if (inList) 0.dp else 8.dp)) {
        if (inList) item { header(false) }
        if (items.isEmpty()) item { Empty(tr("Nothing here")) }
        items(videos.withIndex().toList(), key = { it.value.id }) { (i, v) ->
            val extra = buildList {
                add(tr("Remove from this list") to { app.store.setInList(key, v, false) })
                if (pl != null && i > 0) add(tr("Move up") to { app.store.movePlaylistItem(key, i, i - 1) })
                if (pl != null && i < videos.size - 1) add(tr("Move down") to { app.store.movePlaylistItem(key, i, i + 1) })
            }
            VideoRow(v, app, extra = extra) { app.watch(v, videos.drop(i + 1)) }
        }
    } }
    if (rename && pl != null) NameDialog(tr("Rename playlist"), pl.title, { rename = false }) { app.store.renamePlaylist(key, it) }
    if (delete && pl != null) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text(tr("Delete this playlist?")) },
        confirmButton = { TextButton(onClick = { delete = false; app.back(); app.store.deletePlaylist(key) }) { Text(tr("Delete")) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text(tr("Cancel")) } },
    )
}

/** Ticks the groups a channel is in; a new group can be made right here. */
@Composable
private fun GroupPicker(app: AppState, cid: String, dismiss: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(lib.subs[cid]?.name?.ifEmpty { null } ?: tr("Groups")) },
        text = {
            Column {
                for (g in lib.groups) Row(
                    Modifier.fillMaxWidth().clickable { app.store.setInGroup(g.name, cid, cid !in g.channels) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(cid in g.channels, onCheckedChange = { on -> app.store.setInGroup(g.name, cid, on) })
                    Text(g.name)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(tr("New group")) }, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val n = name.trim()
                        if (n.isNotEmpty()) { app.store.newGroup(n); app.store.setInGroup(n, cid, true); name = "" }
                    }) { Text(tr("Add")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Done")) } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(150) }, singleLine = true, placeholder = { Text(tr("Name")) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { ok(name.trim()); dismiss() } }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(tr("Cancel")) } },
    )
}

/** Save to playlist: tick the playlists the video should be in, or make a new one. */
@Composable
fun PlaylistPicker(v: Video, app: AppState, dismiss: () -> Unit) {
    val lib by app.store.lib.state.collectAsState()
    var making by remember { mutableStateOf(false) }
    // as YouTube: Watch later first, then the playlists, newest first
    val lists = listOf("wl" to tr("Watch later")) + lib.playlists.entries.sortedByDescending { it.value.updated }.map { it.key to it.value.title }
    // signed in: the account's own "Save to..." list, so its playlists show (not only the ones kept here) and a video
    // already in the account's Watch later or a playlist shows ticked
    var targets by remember(v.id) { mutableStateOf<List<app.tubeless.engine.SaveTarget>>(emptyList()) }
    val remoteOn = remember(v.id) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(v.id, app.account) {
        if (app.feedSource.signedIn) targets = YouTubeAccount.saveTargets(v.id).orEmpty().onEach { if (it.id !in remoteOn) remoteOn[it.id] = it.has }
    }
    val scope = rememberCoroutineScope()
    @Composable
    fun Rows() {
        for ((id, title) in lists) {
            // the local list, or what the account says for the same list
            val on = app.store.inList(id, v.id) || remoteOn[if (id == "wl") "WL" else id] == true
            fun flip() { remoteOn[if (id == "wl") "WL" else id] = !on; app.store.setInList(id, v, !on) }
            Row(Modifier.fillMaxWidth().clickable { flip() }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { flip() })
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // the account's playlists that aren't kept here: straight to the account, put back with a toast if it refuses
        for (t in targets) {
            if (t.id == "WL" || lib.playlists.containsKey(t.id)) continue
            val on = remoteOn[t.id] == true
            fun flip() {
                remoteOn[t.id] = !on
                scope.launch {
                    val ok = YouTubeAccount.editPlaylist(t.id, add = if (!on) listOf(v.id) else emptyList(), remove = if (on) listOf(v.id) else emptyList())
                    if (!ok) { remoteOn[t.id] = on; app.toast(tr("Couldn't update the playlist on your YouTube account")) }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { flip() }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { flip() })
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (LocalCompact.current) {
        // a phone: a sheet from the bottom, "Save video to…", the lists, and New playlist at its foot
        androidx.compose.material3.ModalBottomSheet(modifier = Modifier.sheetTop(), onDismissRequest = dismiss) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Save video to..."), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = dismiss) { Text(tr("Done")) }
                }
                Column(Modifier.padding(horizontal = 8.dp)) { Rows() }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clickable { making = true }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.Add.outline, null)
                    Text(tr("New playlist"), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    } else AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(tr("Save video to...")) },
        text = {
            Column {
                Rows()
                TextButton(onClick = { making = true }) {
                    Icon(Sym.Add.outline, null, Modifier.size(18.dp)); Text(tr("New playlist"), Modifier.padding(start = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(tr("Done")) } },
    )
    if (making) NameDialog(tr("New playlist"), "", { making = false }) { app.store.newPlaylist(it, v) }
}

/**
 * Asks for files (several at once: Takeout gives one CSV per playlist) and merges whatever is in
 * them into the library.
 */
fun importFile(app: AppState, done: (Boolean) -> Unit = {}) {
    app.platform.openTextFiles { files ->
        if (files.isEmpty()) return@openTextFiles done(false)
        var total: ImportResult? = null
        val formats = LinkedHashSet<String>()
        var profile = false
        for ((name, text) in files) {
            // a taste profile someone exported lands next to the other profiles
            if (importProfile(app, text) != null) { profile = true; continue }
            val p = Transfer.parse(text, name)
            if (p.format == null || p.empty) continue
            val r = Transfer.merge(app.store, p)
            formats += r.format
            total = total?.let { it.copy(subs = it.subs + r.subs, hist = it.hist + r.hist, playlists = it.playlists + r.playlists, videos = it.videos + r.videos) } ?: r
        }
        val t = total
        if (t == null) {
            if (!profile) app.toast(tr("Couldn't find anything to import in this file"))
            return@openTextFiles done(profile)
        }
        app.fillSubInfo()
        app.sync.importedLocally()
        app.toast(
            tr("Imported from {f}: {s} subscriptions, {h} history, {p} playlists")
                .replace("{f}", formats.joinToString(", ")).replace("{s}", "${t.subs}").replace("{h}", "${t.hist}").replace("{p}", "${t.playlists}"),
        )
        done(true)
    }
}
