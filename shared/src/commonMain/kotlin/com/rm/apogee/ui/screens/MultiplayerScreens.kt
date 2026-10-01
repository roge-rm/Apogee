package com.rm.apogee.ui.screens

import androidx.compose.foundation.lazy.rememberLazyListState
import com.rm.apogee.ui.components.verticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.net.ServerAddress
import com.rm.apogee.ui.components.ApogeeButton
import com.rm.apogee.ui.components.Backdrop
import com.rm.apogee.ui.components.SectionHeading
import com.rm.apogee.ui.theme.ApogeeAlpha
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens
import com.rm.apogee.ui.theme.TelemetryTextStyle
import com.rm.apogee.ui.theme.alpha

@Composable
fun HostGameScreen(
    serverName: String,
    onServerNameChange: (String) -> Unit,
    onStartHosting: () -> Unit,
    /** Hosting the career world instead of the sandbox. */
    career: Boolean = false,
    onCareer: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("Host a Game", style = MaterialTheme.typography.titleLarge, color = Color.White)

        // Which world on this phone the others join.
        SectionHeading("World", contentModifier)
        PillRow(listOf("Career" to true, "Sandbox" to false), career, onCareer)
        Spacer(Modifier.height(6.dp))
        Text(
            if (career) "Everyone who joins starts their own career" else "Everything unlocked, for everyone",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.alpha(ApogeeAlpha.SECONDARY),
            textAlign = TextAlign.Center,
            modifier = contentModifier,
        )

        SectionHeading("Game name", contentModifier)
        OutlinedTextField(
            value = serverName,
            onValueChange = onServerNameChange,
            singleLine = true,
            modifier = contentModifier,
        )

        Spacer(Modifier.height(24.dp))
        ApogeeButton(
            "Start hosting",
            onStartHosting,
            contentModifier,
        )
        Spacer(Modifier.height(12.dp))
        com.rm.apogee.ui.components.BackButton(onBack)
    }
}

@Composable
fun JoinGameScreen(
    browser: ServerBrowser,
    connectingTo: String?,
    error: String?,
    manualAddress: String,
    onManualAddressChange: (String) -> Unit,
    defaultPort: Int,
    onJoin: (DiscoveredServer) -> Unit,
    onJoinAddress: (ServerAddress) -> Unit,
    onBack: () -> Unit = {},
) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth, title = "Join a Game", onBack = onBack) { contentModifier ->

        if (connectingTo != null) {
            CircularProgressIndicator(color = ApogeeColors.Accent)
            Spacer(Modifier.height(12.dp))
            Text(
                "Connecting to $connectingTo\u2026",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.alpha(ApogeeAlpha.BODY),
            )
        } else {
            if (browser.servers.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = ApogeeColors.Accent,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "   Looking for games on this network\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    // Discovery is a UDP broadcast, which a VPN, guest network or other subnet
                    // drops silently. The game is still reachable by address.
                    "A VPN or a guest network hides games, so type the address instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    textAlign = TextAlign.Center,
                    modifier = contentModifier,
                )
            } else {
                val list = rememberLazyListState()
                LazyColumn(contentModifier.heightIn(max = 260.dp).verticalScrollbar(list), state = list) {
                    items(browser.servers, key = { it.key }) { server ->
                        ServerRow(server, browser.reasonFor(server), onJoin)
                    }
                }
            }

            ManualAddressEntry(
                address = manualAddress,
                onAddressChange = onManualAddressChange,
                defaultPort = defaultPort,
                onConnect = onJoinAddress,
                modifier = contentModifier,
            )
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = ApogeeColors.Danger,
                textAlign = TextAlign.Center,
                modifier = contentModifier,
            )
        }
    }
}

/** Connecting to a typed address. Always shown: on a VPN or another network it's the only way. */
@Composable
private fun ManualAddressEntry(
    address: String,
    onAddressChange: (String) -> Unit,
    defaultPort: Int,
    onConnect: (ServerAddress) -> Unit,
    modifier: Modifier,
) {
    val parsed = ServerAddress.parse(address, defaultPort)

    SectionHeading("Connect by address", modifier)
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        singleLine = true,
        placeholder = {
            Text(
                "10.0.0.233",
                color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
            )
        },
        // A URI keyboard has dots and digits up front and doesn't capitalise or autocorrect.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { parsed?.let(onConnect) }),
        modifier = modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Port $defaultPort unless you add one",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
    Spacer(Modifier.height(8.dp))
    ApogeeButton(
        "Connect",
        { parsed?.let(onConnect) },
        modifier,
        enabled = parsed != null,
    )
}

@Composable
private fun ServerRow(
    server: DiscoveredServer,
    incompatibility: String?,
    onJoin: (DiscoveredServer) -> Unit,
) {
    val joinable = incompatibility == null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Dimens.CornerSmall))
            .background(
                if (joinable) ApogeeColors.Accent.alpha(ApogeeAlpha.CONTROL_FILL)
                else Color.White.alpha(ApogeeAlpha.FILL_FAINT)
            )
            .clickable(enabled = joinable) { onJoin(server) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                server.beacon.serverName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (joinable) Color.White else Color.White.alpha(ApogeeAlpha.BORDER),
            )
            Text(
                // Says why a game can't be joined.
                incompatibility ?: "${server.beacon.address}  ·  " +
                    "${server.beacon.players} playing",
                style = MaterialTheme.typography.labelSmall,
                color = if (joinable) Color.White.alpha(ApogeeAlpha.SUBTITLE)
                else ApogeeColors.Caution,
            )
        }
        if (joinable) {
            Text("JOIN", style = TelemetryTextStyle, color = ApogeeColors.Accent)
        }
    }
}
