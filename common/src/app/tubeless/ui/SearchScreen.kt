package app.tubeless.ui

import app.tubeless.rec.Learn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.tubeless.engine.Video
import app.tubeless.engine.YouTube
import app.tubeless.engine.SearchOptions
import app.tubeless.engine.SearchRefine
import app.tubeless.engine.Sys
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider

@Composable
fun SearchScreen(app: AppState, initial: String) {
    var text by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf(SearchOptions()) }
    var refine by remember { mutableStateOf(SearchRefine()) }
    var filters by remember { mutableStateOf(false) }
    var unwatched by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // read as state, so switching list/grid or moving the box redraws at once
    val prefs by app.store.settings.state.collectAsState()

    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        suggestions = emptyList()
        // the box lets go of the keyboard before this screen is replaced: on the iPhone and iPad the keyboard
        // otherwise stays tied to the old box, and nothing typed from the second search on arrived
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        // a pasted link opens what it points to
        if (t.startsWith("http") || t.startsWith("@") || t.contains("youtu")) {
            scope.launch {
                val r = runCatching { YouTube.resolve(t) }.getOrNull()
                when (r?.first) {
                    "video" -> app.watch(Video(r.second, ""))
                    "playlist" -> app.go(Screen.Playlist(r.second))
                    "channel" -> app.go(Screen.Channel(r.second))
                    else -> { query = t }
                }
            }
            return
        }
        query = t
        app.stack[app.stack.size - 1] = Screen.Search(t)
        // what you search for is a light signal for Home (not with history off)
        if (!app.store.settings.value.historyOff) Learn.searched(app.store, t)
        if (!app.store.settings.value.historyOff) app.store.settings.update { s ->
            s.copy(recentSearches = (listOf(t) + s.recentSearches.filter { !it.equals(t, true) }).take(30))
        }
    }

    fun forget(q: String) = app.store.settings.update { s -> s.copy(recentSearches = s.recentSearches - q) }

    LaunchedEffect(text) {
        if (text == query || text.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(200)
        suggestions = runCatching { YouTube.suggestions(text) }.getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { if (initial.isEmpty()) runCatching { focus.requestFocus() } }

    @Composable
    fun SearchBox(modifier: Modifier = Modifier) {
        Row(modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.back() }) { Icon(Sym.ArrowBack.fill, tr("Back")) }
            // a round, filled field (Material 3's search bar), not a boxed outline
            SearchField(text, { text = it; if (it.isEmpty()) runCatching { focus.requestFocus() } }, tr("Search YouTube"),
                Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus), onSearch = { submit(text) })
            // YouTube's mic beside the field: speak, and it searches when you stop
            MicButton(app, Modifier.padding(end = 8.dp)) { t -> text = t; submit(t) }
        }
    }

    val bottom = prefs.searchAtBottom && app.platform.touch
    // the website layout: the masthead has the search box (and its suggestions)
    val web = webDesk(app)
    Column(Modifier.fillMaxSize()) {
        if (!bottom && !web) SearchBox()
        // the suggestions or the results; with the box at the bottom it sits under them
        Column(Modifier.weight(1f).fillMaxWidth()) {
        // recent searches: all of them in an empty box, the ones that match above the suggestions while typing
        val recent = prefs.recentSearches.filter { text.isBlank() || (it.startsWith(text.trim(), true) && !it.equals(text.trim(), true)) }
            .take(if (text.isBlank()) 30 else 3)
        val typing = text.isNotBlank() && text != query
        if (suggestions.isNotEmpty() || (recent.isNotEmpty() && (text.isBlank() || typing))) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (text.isBlank()) item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Recent searches"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { app.store.settings.update { it.copy(recentSearches = emptyList()) } }) { Text(tr("Clear all")) }
                    }
                }
                items(recent, key = { "r$it" }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { text = s; submit(s) }.padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.History.outline, null)
                        Text(s, Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { forget(s) }) { Icon(Sym.Close.outline, tr("Remove")) }
                    }
                }
                items(suggestions.filter { s -> recent.none { it.equals(s, true) } }, key = { "s$it" }) { s ->
                    Row(Modifier.fillMaxWidth().clickable { text = s; submit(s) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Sym.Search.outline, null)
                        Text(s, Modifier.padding(start = 16.dp))
                    }
                }
            }
            return@Column
        }
        if (query.isEmpty()) return@Column
        val compact = LocalCompact.current
        val grid = prefs.searchGrid
        // YouTube's chips: All, Shorts, Unwatched, Videos, Recently uploaded, Live; Filters on the right
        val lib by app.store.lib.state.collectAsState()
        val chips = listOf("All", "Shorts", "Unwatched", "Videos", "Recently uploaded", "Live")
        fun chipOn(k: String) = when (k) {
            "All" -> filter.type == 0 && !unwatched && filter.date == 0 && !filter.live
            "Shorts" -> filter.type == 9
            "Unwatched" -> unwatched
            "Videos" -> filter.type == 1 && filter.date == 0 && !filter.live
            "Recently uploaded" -> filter.date == 3 && filter.type == 0
            else -> filter.live
        }
        fun chipPick(k: String) {
            unwatched = k == "Unwatched"
            filter = when (k) {
                "All", "Unwatched" -> filter.copy(type = 0, date = 0, live = false)
                "Shorts" -> filter.copy(type = 9, date = 0, live = false)
                "Videos" -> filter.copy(type = 1, date = 0, live = false)
                "Recently uploaded" -> filter.copy(type = 0, date = 3, live = false)
                else -> filter.copy(type = 0, date = 0, live = true)
            }
        }
        val asked = Parity.chip
        LaunchedEffect(asked) { if (asked != null && asked in chips) { chipPick(asked); Parity.chip = null } }
        if (Parity.on("filters") && !filters) LaunchedEffect(Unit) { filters = true; Parity.flags.remove("filters") }
        Row(Modifier.let { if (web) it.widthIn(max = 1120.dp).padding(start = 24.dp, end = 12.dp) else it }, verticalAlignment = Alignment.CenterVertically) {
            if (web) {
                ChipBar(Modifier.weight(1f), end = {
                    val n = filter.count + refine.count
                    Row(Modifier.padding(start = 12.dp).clip(RoundedCornerShape(18.dp)).clickable { filters = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Filters") + if (n > 0) " ($n)" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Icon(Sym.Tune.outline, null, Modifier.padding(start = 6.dp).size(20.dp))
                    }
                }) { for (k in chips) WebChip(tr(k), chipOn(k)) { chipPick(k) } }
            } else LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val n = filter.count + refine.count
                    Chip(n > 0, onClick = { filters = true }, label = tr("Filters") + if (n > 0) " ($n)" else "")
                }
                items(chips) { k -> Chip(chipOn(k), onClick = { chipPick(k) }, label = tr(k)) }
            }
            // phones always show one card per row; wider screens can pick a list or a grid
            if (!compact && !app.platform.tv) IconButton(onClick = { app.store.settings.update { it.copy(searchGrid = !grid) } }) {
                if (grid) Icon(Sym.ViewList.outline, tr("Show as a list"))
                else Icon(Sym.GridView.outline, tr("Show as a grid"))
            }
        }
        if (filters) SearchFilters(filter, { filter = it }, refine, { refine = it }) { filters = false }
        val pager = rememberPager(query to filter, { YouTube.search(query, filter) }, { YouTube.searchMore(it) })
        val loaded = pager.items.filter { !(app.noShorts && it is Video && it.short) }
        // smoke/E2E: the results page has its first answer (or an error)
        LaunchedEffect(pager.started, pager.items.isEmpty(), pager.error) {
            if (pager.started && !pager.loading) E2E.log("search_results", "query" to query, "items" to pager.items.size, "videos" to pager.items.count { it is Video }, "error" to pager.error)
        }
        // refined: only within what is loaded, so more is loaded on request, not on reaching the end
        val seen = remember(lib.hist, lib.pos) { lib.hist.map { it.id }.toHashSet() + lib.pos.keys }
        val unseen = if (unwatched) loaded.filter { it !is Video || it.id !in seen } else loaded
        val shown = if (refine.active) refine.apply(unseen, Sys.nowMs() / 1000) else unseen
        when {
            pager.items.isEmpty() && pager.loading -> Skeleton(rows = !compact && !grid && !app.platform.tv, wide = true)
            // YouTube out of reach: the same page as Home and Shorts (Retry, Go to downloads)
            pager.items.isEmpty() && pager.error != null && pager.error in setOf("No connection to YouTube", tr("No connection to YouTube")) ->
                YouAreOffline(app) { scope.launch { pager.load() } }
            pager.items.isEmpty() && pager.error != null -> Problem(pager.error!!) { scope.launch { pager.load() } }
            pager.items.isEmpty() && pager.started -> Empty(tr("No results"))
            else -> ItemGrid(
                shown, app,
                loading = pager.loading, onEnd = if (refine.active) null else ({ pager.loadMore() }),
                rows = !compact && !grid && !app.platform.tv, wideRows = true, shortsShelves = !refine.active,
                header = if (!refine.active) null else ({
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        val videos = loaded.count { it is Video }
                        Text(if (shown.isEmpty()) tr("No matching videos in the loaded results")
                            else tr("{m} of {n} loaded videos match. This order and view range only apply to the results loaded so far.")
                                .replace("{n}", "$videos").replace("{m}", "${shown.size}"),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pager.next != null) TextButton(onClick = { scope.launch { pager.loadMore() } }, enabled = !pager.loading) { Text(tr("Load more results")) }
                        else Text(tr("No more results to load."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }),
            )
        }
        }
        if (bottom) SearchBox(Modifier.imePadding())
    }
}

/** searches from anywhere (the masthead): remembered as a recent search, a light signal for Home; links open what they point to */
fun searchFor(app: AppState, q: String) {
    val t = q.trim()
    if (t.isEmpty()) return
    if (t.startsWith("http") || t.startsWith("@") || t.contains("youtu.be/") || t.contains("youtube.com/")) {
        app.scope.launch {
            val r = runCatching { YouTube.resolve(t) }.getOrNull()
            when (r?.first) {
                "video" -> app.watch(Video(r.second, ""))
                "playlist" -> app.go(Screen.Playlist(r.second))
                "channel" -> app.go(Screen.Channel(r.second))
                else -> app.go(Screen.Search(t))
            }
        }
        return
    }
    if (!app.store.settings.value.historyOff) {
        Learn.searched(app.store, t)
        app.store.settings.update { s -> s.copy(recentSearches = (listOf(t) + s.recentSearches.filter { !it.equals(t, true) }).take(30)) }
    }
    if (app.current is Screen.Search) app.stack[app.stack.size - 1] = Screen.Search(t) else app.go(Screen.Search(t))
}

/**
 * YouTube's search filters, a centered window of five columns (Type, Duration, Upload date, Features,
 * Prioritize): a choice applies at once, chosen again it is removed. Below them what YouTube doesn't
 * offer, done here on the loaded results: fewest views, oldest first, a view range.
 */
@Composable
private fun SearchFilters(o: SearchOptions, set: (SearchOptions) -> Unit, r: SearchRefine, setR: (SearchRefine) -> Unit, close: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // QA: when the filters window opens and closes (Esc must close it and nothing else)
    androidx.compose.runtime.DisposableEffect(Unit) { E2E.log("dialog", "name" to "filters", "open" to true); onDispose { E2E.log("dialog", "name" to "filters", "open" to false) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.widthIn(max = 960.dp).fillMaxWidth(0.92f), shape = RoundedCornerShape(16.dp), color = c.surface, shadowElevation = 8.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Search filters"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = close) { Icon(Sym.Close.outline, tr("Close")) }
                }
                @Composable
                fun RowScopeCol(title: String, options: List<Pair<String, Boolean>>, pick: (Int) -> Unit, m: Modifier) {
                    Column(m.padding(top = 16.dp, end = 12.dp)) {
                        Text(tr(title).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, letterSpacing = 0.6.sp)
                        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp), color = c.outlineVariant)
                        options.forEachIndexed { i, (label, on) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { pick(i) }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(tr(label), fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                if (on) Icon(Sym.Close.outline, tr("Remove"), Modifier.size(16.dp))
                            }
                        }
                    }
                }
                val types = listOf(1 to "Videos", 9 to "Shorts", 2 to "Channels", 3 to "Playlists", 4 to "Movies")
                val durations = listOf(4 to "Under 3 minutes", 5 to "3 - 20 minutes", 2 to "Over 20 minutes")
                val dates = listOf(2 to "Today", 3 to "This week", 4 to "This month", 5 to "This year")
                val feats = listOf(8 to "Live", 14 to "4K", 4 to "HD", 5 to "Subtitles/CC", 6 to "Creative Commons", 15 to "360°", 26 to "VR180", 7 to "3D", 25 to "HDR", 23 to "Location", 9 to "Purchased")
                val prio = listOf(0 to "Relevance", 3 to "Popularity")
                // a phone: two columns sharing the width (five of 150 dp left a gap and a short line on the right)
                val narrow = LocalCompact.current
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (narrow) 2 else Int.MAX_VALUE) {
                    val w = if (narrow) Modifier.weight(1f) else Modifier.widthIn(min = 150.dp).fillMaxWidth(0.19f)
                    RowScopeCol("Type", types.map { it.second to (o.type == it.first) }, { i -> set(o.copy(type = if (o.type == types[i].first) 0 else types[i].first)); close() }, w)
                    RowScopeCol("Duration", durations.map { it.second to (o.duration == it.first) }, { i -> set(o.copy(duration = if (o.duration == durations[i].first) 0 else durations[i].first)); close() }, w)
                    RowScopeCol("Upload date", dates.map { it.second to (o.date == it.first) }, { i -> set(o.copy(date = if (o.date == dates[i].first) 0 else dates[i].first)); close() }, w)
                    RowScopeCol("Features", feats.map { (n, l) -> l to (if (n == 8) o.live else n in o.features) }, { i ->
                        val n = feats[i].first
                        set(if (n == 8) o.copy(live = !o.live) else o.copy(features = if (n in o.features) o.features - n else o.features + n)); close()
                    }, w)
                    RowScopeCol("Prioritize", prio.map { it.second to (o.sort == it.first && r.sort == 0 && it.first != 0) }, { i -> setR(r.copy(sort = 0)); set(o.copy(sort = prio[i].first)); close() }, w)
                }
                // done on this device, on what has loaded
                Text(tr("More, on this device").uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(top = 24.dp))
                HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 10.dp), color = c.outlineVariant)
                // a phone: the two chips, then the two view limits side by side (in one row they ran off the window)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically, maxItemsInEachRow = if (narrow) 2 else Int.MAX_VALUE) {
                    for ((v, label) in listOf(1 to "Fewest views", 2 to "Oldest first")) FilterChip(r.sort == v, onClick = {
                        setR(r.copy(sort = if (r.sort == v) 0 else v)); set(o.copy(sort = 0))
                    }, label = { Text(tr(label)) })
                    @Composable
                    fun limit(label: String, now: Long?, pick: (Long?) -> Unit, m: Modifier) {
                        var t by remember(now == null) { mutableStateOf(now?.toString() ?: "") }
                        OutlinedTextField(t, { x -> t = x.filter { it.isDigit() }.take(15); pick(t.toLongOrNull()) }, m,
                            label = { Text(tr(label)) }, placeholder = { Text(tr("No limit")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    val field = if (narrow) Modifier.weight(1f) else Modifier.width(170.dp)
                    limit("Minimum views", r.minViews, { setR(r.copy(minViews = it)) }, field)
                    limit("Maximum views", r.maxViews, { setR(r.copy(maxViews = it)) }, field)
                }
                if (r.minViews != null && r.maxViews != null && r.maxViews < r.minViews)
                    Text(tr("Maximum views must be at least the minimum"), color = c.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { set(SearchOptions(type = o.type)); setR(SearchRefine()) }) { Text(tr("Reset")) }
                    TextButton(onClick = close) { Text(tr("Done")) }
                }
            }
        }
    }
}
