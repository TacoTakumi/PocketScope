package com.pocketscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Non-dismissable dialog for approving incoming network connections.
 *
 * Presented when a non-whitelisted IP connects to the INDI server.
 * The connection is held pending until the user responds (up to 60 seconds).
 *
 * @param ip The IP address requesting connection.
 * @param onAllowOnce Allow this single connection (no whitelist persistence).
 * @param onAlwaysAllow Allow and persist the IP to the whitelist for future auto-approval.
 * @param onDeny Reject the connection.
 */
@Composable
fun ApprovalDialog(
    ip: String,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-dismissable: user MUST respond */ },
        title = {
            Text(
                text = "New Connection",
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Text(
                text = "$ip wants to connect",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
            )
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onAlwaysAllow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Always Allow", fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onAllowOnce,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Allow Once", fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onDeny,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Deny",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = null
    )
}
