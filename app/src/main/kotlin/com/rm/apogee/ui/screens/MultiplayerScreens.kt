package com.rm.apogee.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
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
) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("Host a Game", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Others on the same network will see this game in their list.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.alpha(ApogeeAlpha.BODY),
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
            subtitle = "You will fly while others join",
        )
    }
}

@Composable
fun JoinGameScreen(
    browser: ServerBrowser,
    connectingTo: String?,
    error: String?,
    onJoin: (DiscoveredServer) -> Unit,
) {
    Backdrop(maxContentWidth = Dimens.PanelContentMaxWidth) { contentModifier ->
        Text("Join a Game", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(8.dp))

        when {
            connectingTo != null -> {
                CircularProgressIndicator(color = ApogeeColors.Accent)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Connecting to $connectingTo…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.alpha(ApogeeAlpha.BODY),
                )
            }

            browser.servers.isEmpty() -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = ApogeeColors.Accent,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "   Looking for games on this network…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Both devices need to be on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.alpha(ApogeeAlpha.SUBTITLE),
                    textAlign = TextAlign.Center,
                    modifier = contentModifier,
                )
            }

            else -> LazyColumn(contentModifier.heightIn(max = 360.dp)) {
                items(browser.servers, key = { it.key }) { server ->
                    ServerRow(server, browser.reasonFor(server), onJoin)
                }
            }
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
                // Naming the reason matters: "different parts" is actionable,
                // a greyed-out row that says nothing is not.
                incompatibility ?: "${server.beacon.address}  ·  " +
                    "${server.beacon.players} flying",
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
