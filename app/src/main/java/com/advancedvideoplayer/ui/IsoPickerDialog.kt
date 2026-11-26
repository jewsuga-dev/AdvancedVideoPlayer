package com.advancedvideoplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.advancedvideoplayer.iso.DiscScan
import com.advancedvideoplayer.iso.DiscTitle

/** Simple "which title?" list shown after opening a Blu-ray / DVD image. */
@Composable
fun IsoPickerDialog(scan: DiscScan, onPick: (DiscTitle) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(scan.kind + " disc – choose what to play") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                scan.titles.forEach { t ->
                    TextButton(onClick = { onPick(t) }, modifier = Modifier.fillMaxWidth()) {
                        Text(t.label, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
