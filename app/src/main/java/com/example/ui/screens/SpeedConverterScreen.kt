package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.example.ui.components.rememberBackgroundTaskStarter

@Composable
fun SpeedConverterScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedUri by viewModel.selectedLocalUri.collectAsState()
    val metadata by viewModel.selectedLocalMetadata.collectAsState()
    val targetSpeed by viewModel.targetLocalSpeed.collectAsState()
    val targetQuality by viewModel.targetLocalQuality.collectAsState()
    val targetFormat by viewModel.targetLocalFormat.collectAsState()
    val targetBitrate by viewModel.targetLocalBitrate.collectAsState()
    val conversionState by viewModel.conversionState.collectAsState()
    val backgroundWorkPaused by viewModel.backgroundWorkPaused.collectAsState()

    var showGuideDialog by remember { mutableStateOf(false) }
    val startBackgroundConversion = rememberBackgroundTaskStarter(
        onStart = viewModel::startLocalConversion,
        onNotificationDenied = {
            viewModel.showActionMessage("لم يُسمح بالإشعارات؛ سيعمل التحويل لكن قد لا يظهر إشعاره في درج الإشعارات.")
        }
    )

    if (showGuideDialog) {
        FeaturePhoneGuideDialog(onDismiss = { showGuideDialog = false })
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
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
        // Calm tinted hero card
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
                        text = "تحويل الصوت وإعادة ترميز الفيديو",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "تغيّر الدقة والسرعة بإعادة ترميز فعلية؛ يمكن أيضًا تحويل الصوت واستخراجه بصيغ متعددة.",
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
                    subtitle = "اختر فيديو لتغيير دقته وسرعته فعليًا أو صوتًا لتحويل صيغته وسرعته",
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

                            IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Default.Edit, contentDescription = "تغيير الملف", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
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

        // Video outputs pass through Media3's real decode, scale, speed and encode pipeline.
        val isVideoSource = metadata?.isVideo == true
        val isVideoTarget = targetFormat == AudioFormat.MP4 || targetFormat == AudioFormat.MKV || targetFormat == AudioFormat.WEBM
        val isVideoTransform = isVideoSource && isVideoTarget
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SectionHeader(
                    title = "إعدادات تحويل الجودة والصيغة",
                    subtitle = when {
                        isVideoTransform -> "إعادة ترميز الفيديو فعليًا بالدقة والسرعة المختارتين"
                        isVideoSource -> "اختر صيغة الصوت المستخرج وسرعته"
                        else -> "اختر صيغة ومعدل جودة الصوت"
                    },
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
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val availableFormats = if (isVideoSource) {
                        listOf(
                            AudioFormat.MP4 to "فيديو MP4",
                            AudioFormat.MKV to "فيديو MKV",
                            AudioFormat.MP3 to "استخراج MP3",
                            AudioFormat.M4A to "استخراج M4A (AAC)",
                            AudioFormat.WAV to "صوت WAV"
                        )
                    } else {
                        listOf(
                            AudioFormat.MP3 to "صوت MP3",
                            AudioFormat.M4A to "صوت M4A (AAC)",
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

                // Actual video scale presets; treated as a maximum short edge and never upscaled.
                if (isVideoTransform) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "حد دقة الفيديو:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${targetQuality.height}p كحد أقصى",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VideoQuality.values().forEach { q ->
                            FilterChip(
                                selected = targetQuality == q,
                                onClick = { viewModel.setTargetLocalQuality(q) },
                                label = { Text(q.label.substringBefore(" ")) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يحافظ التحويل على تناسب أبعاد الفيديو؛ لا تُرفع دقة المصدر الأصغر من الاختيار.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (targetFormat == AudioFormat.WAV) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "WAV يحفظ الصوت PCM غير المضغوط؛ معدل البت تحدده العينة والقنوات ولا ينطبق عليه اختيار ضغط MP3/AAC.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    subtitle = if (isVideoTransform) "تغيير سرعة الفيديو والصوت متزامنًا مع الحفاظ على النبرة الطبيعية" else "تسريع أو تبطيء الصوت مع الحفاظ على النبرة الطبيعية",
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
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { s ->
                        val isSelected = targetSpeed == s
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setTargetLocalSpeed(s) },
                            enabled = true,
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
                        onClick = startBackgroundConversion,
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
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                if (backgroundWorkPaused) viewModel.resumeBackgroundWork()
                                else viewModel.pauseBackgroundWork()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(
                                if (backgroundWorkPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (backgroundWorkPaused) "استئناف التحويل" else "إيقاف مؤقت")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.cancelBackgroundWork() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("إلغاء التحويل في الخلفية")
                        }
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
                            tint = MaterialTheme.colorScheme.tertiary,
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
