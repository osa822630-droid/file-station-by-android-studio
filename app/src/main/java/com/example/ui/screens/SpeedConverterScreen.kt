package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioBitrate
import com.example.model.AudioFormat
import com.example.model.ProcessingState
import com.example.model.VideoQuality
import com.example.ui.MainViewModel
import com.example.ui.components.FeaturePhoneGuideDialog
import com.example.ui.components.FormatBadge
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.SectionHeader
import com.example.ui.components.SpeedBadge
import com.example.ui.components.formatDuration
import com.example.ui.components.formatFileSize
import com.example.ui.theme.EmeraldSuccess

@Composable
fun SpeedConverterScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedUri by viewModel.selectedLocalUri.collectAsState()
    val metadata by viewModel.selectedLocalMetadata.collectAsState()
    val targetSpeed by viewModel.targetLocalSpeed.collectAsState()
    val targetFormat by viewModel.targetLocalFormat.collectAsState()
    val targetBitrate by viewModel.targetLocalBitrate.collectAsState()
    val targetQuality by viewModel.targetLocalQuality.collectAsState()
    val conversionState by viewModel.conversionState.collectAsState()

    var showGuideDialog by remember { mutableStateOf(false) }

    if (showGuideDialog) {
        FeaturePhoneGuideDialog(onDismiss = { showGuideDialog = false })
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onLocalFileSelected(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("converter_screen_column")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Red Hero Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Transform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "محول جودات الفيديو والصوت والسرعات",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "تقليل جودة الفيديو (من الأعلى للأقل 1080p -> 720p/480p) أو تحويل واستخراج الصوت وتسريع التشغيل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }

                IconButton(onClick = { showGuideDialog = true }) {
                    Icon(Icons.Default.HelpOutline, contentDescription = "دليل المحطة", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // File Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(
                    title = "اختيار ملف الفيديو أو الصوت من جهازك",
                    subtitle = "اختر أي فيديو أو صوتية لتغيير الدقة أو استخراج الصوت أو تغيير السرعة",
                    icon = Icons.Default.FolderOpen
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (metadata != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (metadata?.isVideo == true) Icons.Default.VideoFile else Icons.Default.AudioFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = metadata?.title ?: "ملف محدد",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "المدة: ${formatDuration(metadata?.durationMs ?: 0L)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FormatBadge(format = metadata?.format ?: "Media")
                                    if (metadata?.resolutionText?.isNotEmpty() == true) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        ResolutionBadge(resolution = metadata?.resolutionText ?: "")
                                    }
                                }
                            }

                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.Edit, contentDescription = "تغيير الملف", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("pick_file_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("اختر ملف فيديو أو صوت من هاتفك", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Quality Downscaling & Format Choice
        val isVideoSource = metadata?.isVideo == true
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(
                    title = "إعدادات تحويل الجودة والصيغة",
                    subtitle = if (isVideoSource) "اختر تقليل دقة الفيديو أو استخراج الصوت منه" else "اختر صيغة ومعدل جودة الصوت",
                    icon = Icons.Default.AutoFixHigh
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Format Target
                Text(
                    text = "الصيغة الناتجة:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val availableFormats = if (isVideoSource) {
                        listOf(
                            AudioFormat.MP4 to "فيديو MP4",
                            AudioFormat.MKV to "فيديو MKV",
                            AudioFormat.MP3 to "استخراج MP3",
                            AudioFormat.WAV to "صوت WAV"
                        )
                    } else {
                        listOf(
                            AudioFormat.MP3 to "صوت MP3",
                            AudioFormat.M4A to "صوت M4A",
                            AudioFormat.WAV to "صوت WAV"
                        )
                    }

                    availableFormats.forEach { (fmt, label) ->
                        val isSelected = targetFormat == fmt
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setTargetLocalFormat(fmt) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // Video Downscale Options (1080p -> 720p, 480p, 360p, 240p)
                val isVideoTarget = targetFormat == AudioFormat.MP4 || targetFormat == AudioFormat.MKV || targetFormat == AudioFormat.WEBM
                if (isVideoSource && isVideoTarget) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "تقليل دقة وجودة الفيديو (Downscaling):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "تحويل من الدقة العالية إلى دقة أقل لتوفير المساحة وتسهيل المشاركة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            VideoQuality.Q720P,
                            VideoQuality.Q480P,
                            VideoQuality.Q360P,
                            VideoQuality.Q240P
                        ).forEach { q ->
                            val isSelected = targetQuality == q
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setTargetLocalQuality(q) },
                                label = { Text(q.label.substringBefore(" ")) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                } else {
                    // Audio Bitrates
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "معدل البت للصوت (Bitrate):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = targetBitrate.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AudioBitrate.values().forEach { br ->
                            val isSelected = targetBitrate == br
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setTargetLocalBitrate(br) },
                                label = { Text(br.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // Speed Console
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(
                    title = "تحديد سرعة التشغيل",
                    subtitle = "تسريع أو تبطيء الصوت مع الحفاظ على النبرة الطبيعية",
                    icon = Icons.Default.Speed
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = String.format("%.2fx", targetSpeed),
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Speed Preset Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { s ->
                        val isSelected = targetSpeed == s
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setTargetLocalSpeed(s) },
                            label = { Text("${s}x") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.previewLocalFile() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_speed_button"),
                        enabled = selectedUri != null
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("معاينة فورية")
                    }

                    Button(
                        onClick = { viewModel.startLocalConversion() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("start_conversion_button"),
                        enabled = selectedUri != null && conversionState !is ProcessingState.Processing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("بدء التحويل", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Conversion Status Display
        when (val state = conversionState) {
            is ProcessingState.Processing -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.stageDescription,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            is ProcessingState.Success -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.playItem(state.item) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل الملف الآن")
                        }
                    }
                }
            }

            is ProcessingState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            else -> {}
        }
    }
}
