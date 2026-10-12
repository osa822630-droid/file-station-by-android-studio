package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ui.MainViewModel

enum class SearchEngine(val label: String, val icon: @Composable () -> Unit) {
    YOUTUBE("يوتيوب (YouTube)", { Icon(Icons.Default.PlayCircle, contentDescription = null) }),
    GOOGLE("جوجل (Google)", { Icon(Icons.Default.Language, contentDescription = null) }),
    DIRECT_FILES("ملفات مباشرة", { Icon(Icons.Default.FolderZip, contentDescription = null) })
}

data class SearchResultItem(
    val title: String,
    val sourceOrAuthor: String,
    val durationOrType: String,
    val url: String,
    val thumbnailUrl: String = "",
    val snippet: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onNavigateToDownloader: (url: String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedEngine by remember { mutableStateOf(SearchEngine.YOUTUBE) }
    val isSearching by viewModel.isSearching.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    val quickTags = listOf(
        "محاضرات MP3",
        "تلاوات خاشعة",
        "وثائقيات MP4",
        "كتب PDF",
        "أناشيد بدون إيقاع",
        "شروحات تقنية"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("search_screen_column")
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "محرك البحث على الإنترنت",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ابحث مباشرة على يوتيوب وجوجل وحمل الفيديوهات والملفات بضغطة زر واحدة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Engine Selection Tabs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SearchEngine.values().forEach { engine ->
                            val selected = selectedEngine == engine
                            FilterChip(
                                selected = selected,
                                onClick = { selectedEngine = engine },
                                label = { Text(engine.label, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = engine.icon,
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_query_input"),
                        placeholder = {
                            Text(
                                if (selectedEngine == SearchEngine.YOUTUBE) "ابحث عن فيديوهات يوتيوب أو صوتيات..."
                                else if (selectedEngine == SearchEngine.GOOGLE) "ابحث في جوجل عن مواقع، ملفات، مستندات..."
                                else "ابحث عن روابط تحميل مباشرة..."
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "مسح")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small
                    )

                    // Search Button
                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                viewModel.performWebSearch(searchQuery.trim(), selectedEngine)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_search_submit"),
                        enabled = searchQuery.isNotBlank() && !isSearching,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري البحث...")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("بحث الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Quick Tag Suggestions
        item {
            Text(
                text = "اقتراحات سريعة:",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(quickTags) { tag ->
                    SuggestionChip(
                        onClick = {
                            searchQuery = tag
                            viewModel.performWebSearch(tag, selectedEngine)
                        },
                        label = { Text(tag) }
                    )
                }
            }
        }

        // Search Results List
        if (searchResults.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "نتائج البحث (${searchResults.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { viewModel.clearSearchResults() }) {
                        Text("مسح النتائج")
                    }
                }
            }

            items(searchResults) { result ->
                SearchResultCard(
                    item = result,
                    onDownloadClick = {
                        onNavigateToDownloader(result.url)
                    },
                    onCopyLink = {
                        clipboardManager.setText(AnnotatedString(result.url))
                        viewModel.showActionMessage("تم نسخ الرابط إلى الحافظة")
                    }
                )
            }
        } else if (!isSearching && searchQuery.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "لا توجد نتائج أو اضغط على 'بحث الآن' لبدء الفحص",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun SearchResultCard(
    item: SearchResultItem,
    onDownloadClick: () -> Unit,
    onCopyLink: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_result_card"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (item.thumbnailUrl.isNotEmpty()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = item.title,
                        modifier = Modifier
                            .size(width = 110.dp, height = 75.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(width = 75.dp, height = 75.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (item.durationOrType.contains("MP4") || item.durationOrType.contains("فيديو"))
                                    Icons.Default.VideoLibrary else Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.sourceOrAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (item.durationOrType.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall
                        ) {
                            Text(
                                text = item.durationOrType,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (item.snippet.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCopyLink) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "نسخ الرابط", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onDownloadClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحميل في File Station", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
