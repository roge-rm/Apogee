package com.rm.apogee.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.ChatToast
import com.rm.apogee.game.HudState
import com.rm.apogee.net.ChatLine
import com.rm.apogee.platform.System
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.alpha

/** The sender's name colour: their suit stripe, or the accent for one without. */
private fun nameColour(line: ChatLine): Color =
    if (line.stripe >= 0) com.rm.apogee.render.SuitColours.stripe(line.stripe).let { Color(it[0], it[1], it[2]) }.lighter()
    else ApogeeColors.Accent

/** Halfway to white, so a dark stripe still reads on the dark backing. */
private fun Color.lighter(): Color = Color(0.5f + red * 0.5f, 0.5f + green * 0.5f, 0.5f + blue * 0.5f)

/** The message button, with a dot while there are lines not yet read. */
@Composable
fun ChatButton(hud: HudState, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier) {
        FilledTonalIconButton(
            onClick = { hud.chatOpen = true; hud.chatUnread = 0 },
            modifier = Modifier.size(size),
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
        }
        if (hud.chatUnread > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(10.dp)
                    .background(ApogeeColors.Accent, CircleShape),
            )
        }
    }
}

/** Lines that just came in, newest at the bottom, each going after [ChatToast.SHOW_NANOS]. */
@Composable
fun ChatToasts(hud: HudState, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    // Ticks while any are up, so the last line goes too.
    LaunchedEffect(hud.chatToasts.isNotEmpty()) {
        while (hud.chatToasts.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
            now = System.nanoTime()
            val kept = hud.chatToasts.filter { now - it.shownAtNanos < ChatToast.SHOW_NANOS }
            if (kept.size != hud.chatToasts.size) hud.chatToasts = kept
        }
    }
    if (hud.chatToasts.isEmpty() || hud.chatOpen) return
    Column(modifier.widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (toast in hud.chatToasts) {
            val left = ChatToast.SHOW_NANOS - (now - toast.shownAtNanos)
            val fade = (left.toFloat() / ChatToast.FADE_NANOS).coerceIn(0f, 1f)
            ChatLineText(
                toast.line,
                Modifier
                    .alpha(fade)
                    .background(Color.Black.alpha(0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ChatLineText(line: ChatLine, modifier: Modifier = Modifier) {
    // From nobody: the game saying what's going on, such as warp.
    if (line.from.isEmpty()) {
        Text(line.text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = ApogeeColors.Accent)
        return
    }
    Row(modifier) {
        Text(
            "${line.from}: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = nameColour(line),
        )
        Text(line.text, style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.BODY))
    }
}

/** Everything said so far, newest at the bottom, and a box to say something. */
@Composable
fun ChatWindow(hud: HudState, onSend: (String) -> Unit, onKick: (String) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    // With a keyboard the box takes it at once. A phone's would cover the chat.
    val box = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (hud.keyboardFirst) runCatching { box.requestFocus() } }
    // Back (Esc on a desktop) closes chat, not the flight behind it.
    com.rm.apogee.ui.BackHandler(enabled = true) { hud.chatOpen = false }
    fun send() {
        if (text.isNotBlank()) onSend(text)
        text = ""
    }
    AlertDialog(
        onDismissRequest = { hud.chatOpen = false },
        title = { Text("Chat") },
        text = {
            Column {
                // Who's in, in their colours: the host marked, anyone in a different time with how
                // far off, and for the host a way to send someone out.
                for (player in hud.players) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            player.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (player.stripe >= 0) com.rm.apogee.render.SuitColours.stripe(player.stripe).let { Color(it[0], it[1], it[2]) }.lighter() else Color.White,
                        )
                        val about = listOfNotNull(
                            "host".takeIf { player.host },
                            player.lead.takeIf { it >= 60.0 }?.let { "+" + com.rm.apogee.core.world.WarpWords.span(it) },
                        ).joinToString(" · ")
                        if (about.isNotEmpty()) Text("  $about", style = MaterialTheme.typography.bodySmall, color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
                        Spacer(Modifier.weight(1f))
                        if (hud.isHost && !player.host) TextButton(onClick = { onKick(player.clientId) }) { Text("Send out") }
                    }
                }
                if (hud.players.isNotEmpty()) Spacer(Modifier.height(12.dp))
                val list = rememberLazyListState()
                if (hud.chatLines.isEmpty()) {
                    Text("Nothing said yet", color = Color.White.alpha(ApogeeAlpha.SUBTITLE))
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScrollbar(list, reverse = true),
                        state = list,
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(hud.chatLines.asReversed(), key = { it.id }) { ChatLineText(it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(com.rm.apogee.server.GameServer.MAX_CHAT_CHARS) },
                    singleLine = true,
                    placeholder = { Text("Say something", color = Color.White.alpha(ApogeeAlpha.SUBTITLE)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(box),
                )
            }
        },
        confirmButton = { TextButton(onClick = ::send, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = { hud.chatOpen = false }) { Text("Close") } },
    )
}
