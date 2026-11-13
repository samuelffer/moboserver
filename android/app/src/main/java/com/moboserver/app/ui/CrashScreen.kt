package com.moboserver.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown on the first launch after a crash: the app never closes "for no
 * reason", the user always gets the trace and a way to send it.
 */
@Composable
fun CrashScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "O app fechou sozinho",
            color = MoboColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Este é o relatório da última falha. Copie ou compartilhe para que o problema possa ser corrigido.",
            color = MoboColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = report,
            color = MoboColors.Warning,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "MoboServer — relatório de erro")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(share, "Compartilhar relatório"))
            },
            colors = ButtonDefaults.buttonColors(containerColor = MoboColors.Lime),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Compartilhar relatório", color = androidx.compose.ui.graphics.Color(0xFF10230A))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(report)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copiar", fontSize = 13.sp)
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Continuar", fontSize = 13.sp)
            }
        }
    }
}
