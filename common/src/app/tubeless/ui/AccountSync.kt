package app.tubeless.ui

import app.tubeless.data.AccountMirror
import app.tubeless.data.Store
import app.tubeless.engine.AccountFeed
import app.tubeless.engine.Video
import app.tubeless.engine.WatchTime
import app.tubeless.engine.YouTubeAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// S4: the signed-in account wired into the app. Owned by S4 (new file).
//  - AccountFeedSource: AppState.feedSource while signed in (Home, Subscriptions, History, WL, Liked).
//  - AccountSync: Store.mirror, so a like, Watch later, subscribe, history removal, "Not interested" or
//    playlist change made anywhere in the app reaches the account as well (paced by WriteLimit).
//  - WatchTime: Playback's hooks drive the watch-time pings.
// Signed out none of this sends anything; screens keep the local library and recommender.

/** The account's lists for [FeedSource] users; null (= use the local list) when signed out or on failure. */
object AccountFeedSource : FeedSource {
    override val signedIn: Boolean get() = YouTubeAccount.signedIn && !YouTubeAccount.expired

    private suspend fun load(kind: FeedKind): AccountFeed? = when (kind) {
        FeedKind.HOME -> YouTubeAccount.home()
        FeedKind.SUBSCRIPTIONS -> YouTubeAccount.subscriptions()
        FeedKind.HISTORY -> YouTubeAccount.history()
        FeedKind.WATCH_LATER -> YouTubeAccount.watchLater()
        FeedKind.LIKED -> YouTubeAccount.liked()
    }

    private fun page(f: AccountFeed?, kind: FeedKind): FeedPage? = f?.takeIf { !it.loggedOut }
        ?.let { FeedPage(if (kind == FeedKind.HISTORY) it.everything else it.videos, it.next) }

    override suspend fun page(kind: FeedKind): FeedPage? =
        if (!signedIn) null else try { page(load(kind), kind) } catch (e: Exception) { null }

    override suspend fun more(kind: FeedKind, token: String): FeedPage? =
        if (!signedIn) null else try { page(YouTubeAccount.more(token), kind) } catch (e: Exception) { null }
}

/** Store.mirror while signed in: each change is sent to the account in the background. */
class AccountSync(private val store: Store, private val scope: CoroutineScope, private val toast: (String) -> Unit = {}) : AccountMirror {
    /** playlist adds/removes wait 1.5 s and go as one edit (copying a playlist is one write, not fifty) */
    private val adds = HashMap<String, MutableList<String>>()
    private val removes = HashMap<String, MutableList<String>>()
    private var flushJob: Job? = null
    /** local playlists being created on the account: what was added meanwhile follows once it has an id */
    private val creating = HashMap<String, MutableList<String>>()

    private fun go(block: suspend () -> Unit) { scope.launch { try { block() } catch (_: Exception) {} } }

    /**
     * A write the user sees the result of: when the account didn't take it (an error, a lapsed sign-in, the write
     * limit), the button goes back to how it was and a toast says so. Before, the failure was swallowed and the app
     * showed a like or subscription the account never got.
     */
    private fun send(fail: String, undo: () -> Unit, block: suspend () -> Boolean) {
        scope.launch {
            val ok = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            if (ok) return@launch
            // put back without sending that back to the account
            val m = store.mirror
            store.mirror = null
            try { undo() } finally { store.mirror = m }
            toast(tr(fail))
        }
    }

    /** the like buttons' params from the signed-in watch page of this video, as youtube.com sends them */
    private fun likeParams(id: String, pick: (app.tubeless.engine.WatchState) -> String?) =
        AccountWatch.state?.takeIf { it.videoId == id }?.let(pick)

    /** a YouTube playlist id (one made here has "HP..." until the account gives it one) */
    private fun remoteList(list: String): String? = when {
        list == "wl" -> "WL"
        list.startsWith("PL") || list.startsWith("OL") -> list
        else -> null
    }

    override fun list(list: String, v: Video, on: Boolean) {
        when (list) {
            "likes" -> send("Couldn't update the like on your YouTube account", { store.setInList("likes", v, !on) }) {
                if (on) YouTubeAccount.like(v.id, likeParams(v.id) { it.likeParams })
                else YouTubeAccount.removeLike(v.id, likeParams(v.id) { it.removeLikeParams })
            }
            "dislikes" -> send("Couldn't update the dislike on your YouTube account", { store.setInList("dislikes", v, !on) }) {
                if (on) YouTubeAccount.dislike(v.id, likeParams(v.id) { it.dislikeParams })
                else YouTubeAccount.removeLike(v.id, likeParams(v.id) { it.removeLikeParams })
            }
            else -> {
                creating[list]?.let { if (on) it += v.id; return }
                val id = remoteList(list) ?: return
                // an add and a remove of the same video before they went cancel out
                if ((if (on) removes else adds)[id]?.remove(v.id) != true) (if (on) adds else removes).getOrPut(id) { ArrayList() } += v.id
                if (flushJob?.isActive != true) flushJob = scope.launch {
                    delay(1500)
                    while (adds.values.any { it.isNotEmpty() } || removes.values.any { it.isNotEmpty() }) flush()
                }
            }
        }
    }

    private suspend fun flush() {
        val ids = (adds.keys + removes.keys).toSet()
        for (id in ids) {
            val a = adds.remove(id).orEmpty().toList()
            val r = removes.remove(id).orEmpty().toList()
            if (a.isEmpty() && r.isEmpty()) continue
            val ok = try { YouTubeAccount.editPlaylist(id, a, r) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            // the list here keeps the change (it may be a batch of many); the toast says the account didn't get it
            if (!ok) toast(tr(if (id == "WL") "Couldn't update Watch later on your YouTube account" else "Couldn't update the playlist on your YouTube account"))
        }
    }

    override fun subscribe(cid: String, on: Boolean) {
        val was = store.lib.value.subs[cid]
        send(if (on) "Couldn't subscribe on your YouTube account" else "Couldn't unsubscribe on your YouTube account", {
            if (on) store.subscribe(cid, "", "", false) else if (was != null) store.subscribe(cid, was.name, was.thumb, true)
        }) { if (on) YouTubeAccount.subscribe(cid) else YouTubeAccount.unsubscribe(cid) }
    }
    override fun historyRemove(id: String) = go { YouTubeAccount.removeFromHistory(id) }
    override fun historyClear() = go { YouTubeAccount.clearHistory() }
    override fun notInterested(v: Video, channel: Boolean) = go { YouTubeAccount.notInterested(v.id, channel) }

    override fun playlistNew(localId: String, title: String, first: Video?) {
        val pending = ArrayList<String>().also { l -> first?.let { l += it.id } }
        creating[localId] = pending
        go {
            val id = YouTubeAccount.createPlaylist(title, listOfNotNull(first?.id))
            val later = creating.remove(localId).orEmpty().drop(if (first != null) 1 else 0)
            if (id != null) {
                // the playlist takes the account's id here, so its later changes reach it
                store.renamePlaylistId(localId, id)
                if (later.isNotEmpty()) YouTubeAccount.editPlaylist(id, later)
            }
        }
    }

    override fun playlistRename(id: String, title: String) { remoteList(id)?.takeIf { it != "WL" }?.let { go { YouTubeAccount.renamePlaylist(it, title) } } }
    override fun playlistDelete(id: String) {
        remoteList(id)?.takeIf { it != "WL" }?.let { YouTubeAccount.forgetPlaylist(it); go { YouTubeAccount.deletePlaylist(it) } }
    }
}

/** Playback's hooks → WatchTime (signed-in watch-time pings, history off = none). Installed once. */
object WatchTimeHooks {
    private var installed = false
    fun install(store: Store) {
        if (installed) return
        installed = true
        Playback.onStart += { _, v, s ->
            if (!store.settings.value.historyOff) WatchTime.start(v.id, (v.lengthSec.takeIf { it > 0 } ?: s.lengthSec).toDouble())
            else WatchTime.stop()
        }
        Playback.onTick += { _, v, st ->
            WatchTime.tick(v.id, st.positionMs / 1000.0, st.durationMs / 1000.0, st.playing)
        }
        Playback.onEnd += { _, v -> WatchTime.end(v.id) }
    }
}
