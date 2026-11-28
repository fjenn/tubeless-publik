package app.tubeless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tubeless.engine.Bell
import app.tubeless.engine.LikeState
import app.tubeless.engine.Video
import app.tubeless.engine.WatchState
import app.tubeless.engine.YouTubeAccount
import kotlinx.coroutines.launch

// The signed-in parts of the watch page (S4's data, engine/YouTubeAccount.kt): the account's like,
// subscription and bell shown on the page, the bell's menu, and the comment box.

object AccountWatch {
    /** the account's state of the video on the page; null signed out or not loaded yet */
    var state by mutableStateOf<WatchState?>(null)

    internal val BellOff by lazy {
        icon24("bellOff", "M20 18.69L7.84 6.14 5.27 3.49 4 4.76l2.8 2.8v.01c-.52.99-.8 2.16-.8 3.42v5l-2 2v1h13.73l2 2L21 19.72l-1-1.03zM12 22c1.11 0 2-.89 2-2h-4c0 1.11.89 2 2 2zm6-7.32V11c0-3.08-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68c-.15.03-.29.08-.42.12-.1.03-.2.07-.3.11h-.01c-.01 0-.01 0-.02.01-.23.09-.46.2-.68.31 0 0-.01 0-.01.01L18 14.68z")
    }
}

/**
 * Signed in: asks the account how this video stands (liked, disliked, subscribed, the bell) and shows
 * it here, without sending anything back (the library mirror is held off while it is applied).
 */
@Composable
fun AccountWatchLoader(app: AppState, v: Video) {
    LaunchedEffect(v.id, app.account) {
        AccountWatch.state = null
        if (!app.feedSource.signedIn) return@LaunchedEffect
        val w = runCatching { YouTubeAccount.watch(v.id) }.getOrNull()?.takeIf { !it.loggedOut } ?: return@LaunchedEffect
        AccountWatch.state = w
        val store = app.store
        val m = store.mirror
        store.mirror = null
        try {
            val liked = store.inList("likes", v.id)
            val disliked = store.inList("dislikes", v.id)
            when (w.like) {
                LikeState.LIKE -> { if (!liked) store.setInList("likes", v, true); if (disliked) store.setInList("dislikes", v, false) }
                LikeState.DISLIKE -> { if (!disliked) store.setInList("dislikes", v, true); if (liked) store.setInList("likes", v, false) }
                LikeState.NONE -> { if (liked) store.setInList("likes", v, false); if (disliked) store.setInList("dislikes", v, false) }
            }
            val cid = w.channelId.ifEmpty { v.channelId }
            val sub = w.subscribed
            if (sub != null && cid.isNotEmpty() && store.lib.value.subs.containsKey(cid) != sub) {
                store.subscribe(cid, v.author, app.playback.details?.avatar ?: v.avatar, sub)
            }
        } finally { store.mirror = m }
    }
}

/** the bell beside "Subscribed": every upload, personalized, none (signed in and subscribed only) */
@Composable
fun ChannelBellButton(app: AppState, channelId: String) {
    val w = AccountWatch.state ?: return
    if (!app.feedSource.signedIn || !app.store.lib.value.subs.containsKey(channelId)) return
    val bell = w.bell ?: return
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.padding(start = 8.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(when (bell) { Bell.ALL -> WebIcons.BellFill; Bell.PERSONALIZED -> WebIcons.Bell; Bell.NONE -> AccountWatch.BellOff }, tr("Notifications"), Modifier.size(22.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((b, label) in listOf(Bell.ALL to tr("All"), Bell.PERSONALIZED to tr("Personalized"), Bell.NONE to tr("None"))) DropdownMenuItem(
                text = { Text(label, fontWeight = if (b == bell) FontWeight.Bold else FontWeight.Normal) },
                leadingIcon = { Icon(when (b) { Bell.ALL -> WebIcons.BellFill; Bell.PERSONALIZED -> WebIcons.Bell; Bell.NONE -> AccountWatch.BellOff }, null) },
                onClick = {
                    open = false
                    scope.launch {
                        val ok = runCatching { YouTubeAccount.setBell(channelId, b, w.bellParams[b]) }.getOrDefault(false)
                        if (ok) AccountWatch.state = AccountWatch.state?.copy(bell = b)
                        else app.toast(tr("Couldn't change this channel's notifications"))
                    }
                },
            )
        }
    }
}

/** "Add a comment…" above the comments, signed in; [posted] reloads them */
@Composable
fun CommentComposer(app: AppState, token: String, posted: suspend () -> Unit) {
    if (!app.feedSource.signedIn) return
    var params by remember(token) { mutableStateOf<String?>(null) }
    LaunchedEffect(token, app.account) { params = runCatching { YouTubeAccount.comments(token) }.getOrNull()?.createParams }
    val p = params ?: return
    val c = MaterialTheme.colorScheme
    var text by remember(token) { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Sym.Person.outline, null) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                if (text.isEmpty()) Text(tr("Add a comment…"), fontSize = 14.sp, color = c.onSurfaceVariant)
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; WebTyping.active = it.isFocused },
                    textStyle = TextStyle(fontSize = 14.sp, color = c.onSurface), cursorBrush = SolidColor(c.onSurface))
            }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(if (focused) c.onSurface else c.outlineVariant))
            if (focused || text.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                TextButton(onClick = { text = "" }) { Text(tr("Cancel")) }
                TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                    sending = true
                    scope.launch {
                        val ok = runCatching { YouTubeAccount.postComment(p, text.trim()) }.getOrDefault(false)
                        sending = false
                        if (ok) { text = ""; app.toast(tr("Comment posted")); posted() }
                        else app.toast(tr("Couldn't post your comment"))
                    }
                }, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (text.isNotBlank()) c.primary.copy(alpha = 0.12f) else c.surfaceVariant)) {
                    Text(tr("Comment"))
                }
            }
        }
    }
}

/**
 * The answer box under a comment (signed in): posts with that comment's createReplyParams, as youtube.com's
 * Reply does. What was sent stays under the comment, since the replies list only shows it after a reload.
 */
@Composable
fun ReplyBox(app: AppState, replyParams: String, close: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var text by remember(replyParams) { mutableStateOf("") }
    var sent by remember(replyParams) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    sent?.let { Text(tr("You") + ": " + it, fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)); return }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Box(Modifier.fillMaxWidth()) {
            if (text.isEmpty()) Text(tr("Add a reply…"), fontSize = 14.sp, color = c.onSurfaceVariant)
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { WebTyping.active = it.isFocused },
                textStyle = TextStyle(fontSize = 14.sp, color = c.onSurface), cursorBrush = SolidColor(c.onSurface))
        }
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(2.dp).background(c.onSurface))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            TextButton(onClick = close) { Text(tr("Cancel")) }
            TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                sending = true
                scope.launch {
                    val t = text.trim()
                    val ok = runCatching { YouTubeAccount.reply(replyParams, t) }.getOrDefault(false)
                    sending = false
                    if (ok) { sent = t; app.toast(tr("Reply posted")) } else app.toast(tr("Couldn't post your reply"))
                }
            }, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (text.isNotBlank()) c.primary.copy(alpha = 0.12f) else c.surfaceVariant)) {
                Text(tr("Reply"))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
