package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.EmeraldSuccess

@Composable
fun FileStationGuideDialog(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "دليل محطة الملفات",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "File Station Pro Hub",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                StationGuideCard(
                    icon = Icons.Default.CloudDownload,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "تنزيل الفيديو وتغيير الدقة والسرعة",
                    description = "يعيد التطبيق فك ترميز الفيديو وترميزه عبر Media3 ومشفرات Android لتطبيق حد الدقة المختار وسرعة 0.5x–2x مع تزامن الصوت والصورة. إخراج MP4 متاح على الأجهزة الداعمة؛ وMKV يعيد تعبئة المسارات المرمزة ويتطلب Android 8.0 أو أحدث. لا تُرفع دقة المصادر الأصغر من الهدف."
                )

                StationGuideCard(
                    icon = Icons.Default.Transform,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "استخراج الصوت من الفيديو",
                    description = "يمكنك استخراج الصوت من الفيديو أو تحويل الملفات الصوتية إلى MP3 بترميز LAME، أو AAC داخل M4A، أو WAV غير مضغوط."
                )

                StationGuideCard(
                    icon = Icons.Default.Search,
                    iconTint = EmeraldSuccess,
                    title = "محرك البحث الشامل (Google & YouTube)",
                    description = "ابحث مباشرة عن أي فيديو أو مقطع أو مستند عبر محرك البحث المدمج وقم بالتحميل الفوري بضغطة زر واحدة دون الحاجة لمغادرة التطبيق."
                )

                StationGuideCard(
                    icon = Icons.Default.InsertDriveFile,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "تنزيل جميع أنواع الملفات والروابط",
                    description = "تنزيل مباشر لكافة أنواع الملفات من أي موقع: مستندات PDF و Word، تطبيقات APK، ملفات مضغوطة ZIP/RAR، وصور وملفات وسائط متنوعة."
                )

                StationGuideCard(
                    icon = Icons.Default.ContentCopy,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    title = "نظام ذكي لمعالجة تعارض الملفات المكررة",
                    description = "عند محاولة تحميل ملف يحمل نفس الاسم، يمنحك التطبيق خيارات فورية: (تحميل على أي حال مع اسم جديد / تحميل واستبدال الملف القديم / إلغاء التحميل)."
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("فهمت، حسناً", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Backward compatibility alias
@Composable
fun FeaturePhoneGuideDialog(onDismiss: () -> Unit) {
    FileStationGuideDialog(onDismiss = onDismiss)
}

@Composable
private fun StationGuideCard(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                )
            }
        }
    }
}
