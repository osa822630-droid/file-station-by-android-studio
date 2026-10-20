package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AudioBitrate
import com.example.model.FileType
import com.example.model.MediaItem
import com.example.ui.MainViewModel
import com.example.ui.components.BitrateBadge
import com.example.ui.components.FormatBadge
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.SpeedBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onNavigateToDownloader: () -> Unit,
    onExportItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val libraryItems by viewModel.libraryItems.collectAsState()
    var selectedFilter by remember { mutableStateOf("الكل") }

    val filteredItems = remember(libraryItems, selectedFilter) {
        when (selectedFilter) {
            "فيديوهات" -> libraryItems.filter { it.isVideo || it.fileType == FileType.VIDEO }
            "صوتيات" -> libraryItems.filter { !it.isVideo && (it.fileType == FileType.AUDIO || it.fileType == FileType.OTHER) }
            "مستندات وتطبيقات" -> libraryItems.filter { it.fileType == FileType.DOCUMENT || it.fileType == FileType.APK || it.fileType == FileType.ARCHIVE }
            "سرعة معدلة" -> libraryItems.filter { it.speed > 1.0f || it.speed < 1.0f }
            else -> libraryItems
        }
    }

    var itemToConvertBitrate by remember { mutableStateOf<MediaItem?>(null) }

    itemToConvertBitrate?.let { item ->
        BitrateConvertDialog(
            item = item,
            onDismiss = { itemToConvertBitrate = null },
            onSelectBitrate = { br ->
                // Handled in converter
                itemToConvertBitrate = null
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen_column")
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Filter chips row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("الكل", "فيديوهات", "صوتيات", "مستندات وتطبيقات", "سرعة معدلة").forEach { filter ->
                val isSelected = selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 120.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "لا توجد ملفات في هذا القسم بعد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "قم بتحميل فيديوهات أو ملفات من الروابط ومحرك البحث لتظهر هنا في محطة الملفات.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToDownloader,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تحميل ملف جديد الآن")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    MediaItemCard(
                        item = item,
                        onPlay = { viewModel.playItem(item) },
                        onOpenExternal = { viewModel.openWithExternalApp(item) },
                        onShare = {
                            val intent = viewModel.repository.shareFileIntent(item)
                            if (intent != null) {
                                context.startActivity(Intent.createChooser(intent, "مشاركة الملف"))
                            }
                        },
                        onExport = { onExportItem(item) },
                        onDelete = { viewModel.deleteItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun MediaItemCard(
    item: MediaItem,
    onPlay: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("media_item_${item.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (item.isVideo) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when (item.fileType) {
                        FileType.VIDEO -> Icons.Default.VideoLibrary
                        FileType.AUDIO -> Icons.Default.Audiotrack
                        FileType.DOCUMENT -> Icons.Default.Description
                        FileType.APK -> Icons.Default.Android
                        FileType.ARCHIVE -> Icons.Default.FolderZip
                        else -> Icons.Default.InsertDriveFile
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.durationMs > 0) {
                            Text(
                                text = formatDuration(item.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(text = " • ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = formatFileSize(item.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (item.fileType == FileType.AUDIO || item.isVideo) {
                    FilledIconButton(
                        onClick = onPlay,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "تشغيل", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    IconButton(onClick = onOpenExternal) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "فتح الملف", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges & Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatBadge(format = item.format)
                    if (item.resolutionText.isNotEmpty()) {
                        ResolutionBadge(resolution = item.resolutionText.substringBefore(" "))
                    }
                    if (item.bitrateKbps > 0 && !item.isVideo) {
                        BitrateBadge(bitrateKbps = item.bitrateKbps)
                    }
                    if (item.speed != 1.0f && item.speed > 0f) {
                        SpeedBadge(speed = item.speed)
                    }
                }

                Row {
                    IconButton(onClick = onOpenExternal, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "فتح في تطبيق خارجي",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onShare, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "مشاركة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onExport, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.DownloadForOffline,
                            contentDescription = "تصدير إلى Downloads أو اختيار موقع الحفظ",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "حذف",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BitrateConvertDialog(
    item: MediaItem,
    onDismiss: () -> Unit,
    onSelectBitrate: (AudioBitrate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحويل الجودة ومعدل البت", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اختر معدل البت المرغوب:")
                AudioBitrate.values().forEach { br ->
                    OutlinedButton(
                        onClick = { onSelectBitrate(br) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${br.label} - ${br.description}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
