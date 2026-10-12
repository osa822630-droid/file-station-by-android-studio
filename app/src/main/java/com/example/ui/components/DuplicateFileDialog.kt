package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DuplicateAction

@Composable
fun DuplicateFileDialog(
    fileName: String,
    onChoice: (DuplicateAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("duplicate_file_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "الملف موجود بالفعل!",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "يوجد ملف بنفس الاسم محفوظ في محطة الملفات:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fileName,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "يرجى اختيار الإجراء المطلوب:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onChoice(DuplicateAction.DOWNLOAD_ANYWAY) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_download_anyway"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("تحميل على أي حال (حفظ كنسخة جديدة)", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onChoice(DuplicateAction.REPLACE_EXISTING) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_replace_existing")
                ) {
                    Text("تحميل واستبدال القديم", fontWeight = FontWeight.SemiBold)
                }

                TextButton(
                    onClick = { onChoice(DuplicateAction.CANCEL) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_cancel_duplicate")
                ) {
                    Text("إلغاء", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        dismissButton = null
    )
}
