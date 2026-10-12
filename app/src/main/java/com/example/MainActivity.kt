package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.DuplicateAction
import com.example.ui.MainViewModel
import com.example.ui.components.DuplicateFileDialog
import com.example.ui.components.FeaturePhoneGuideDialog
import com.example.ui.components.PlayerBar
import com.example.ui.screens.DownloadScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SpeedConverterScreen
import com.example.ui.theme.AudioSpeedTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AudioSpeedTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val actionMessage by viewModel.actionMessage.collectAsState()
                val playerState by viewModel.playerState.collectAsState()
                val duplicateConflict by viewModel.duplicateConflict.collectAsState()
                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

                var selectedTab by remember { mutableIntStateOf(0) }
                var showGuideDialog by remember { mutableStateOf(false) }

                LaunchedEffect(actionMessage) {
                    actionMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.dismissActionMessage()
                    }
                }

                if (showGuideDialog) {
                    FeaturePhoneGuideDialog(onDismiss = { showGuideDialog = false })
                }

                // Duplicate File Prompt: (تحميل على اي حال / تحميل واستبدال القديم / الغاء)
                duplicateConflict?.let { conflict ->
                    DuplicateFileDialog(
                        fileName = conflict.fileName,
                        onChoice = { action ->
                            viewModel.resolveDuplicateConflict(action)
                        },
                        onDismiss = {
                            viewModel.resolveDuplicateConflict(DuplicateAction.CANCEL)
                        }
                    )
                }

                val safeTextToolbar = remember {
                    object : androidx.compose.ui.platform.TextToolbar {
                        override val status: androidx.compose.ui.platform.TextToolbarStatus
                            get() = androidx.compose.ui.platform.TextToolbarStatus.Hidden
                        override fun hide() {}
                        override fun showMenu(
                            rect: androidx.compose.ui.geometry.Rect,
                            onCopyRequested: (() -> Unit)?,
                            onPasteRequested: (() -> Unit)?,
                            onCutRequested: (() -> Unit)?,
                            onSelectAllRequested: (() -> Unit)?
                        ) {}
                    }
                }

                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalTextToolbar provides safeTextToolbar
                ) {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("main_scaffold"),
                        contentWindowInsets = WindowInsets.safeDrawing,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "File Station",
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = "محطة تنزيل وإدارة الملفات",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    FilledTonalButton(
                                        onClick = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            showGuideDialog = true
                                        },
                                        modifier = Modifier.testTag("guide_button"),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("دليل المحطة", style = MaterialTheme.typography.labelMedium)
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.background
                                )
                            )
                        },
                        bottomBar = {
                            Column {
                                // Persistent Mini Player if an item is playing or loaded
                                if (playerState.currentItem != null) {
                                    PlayerBar(
                                        playerState = playerState,
                                        onTogglePlayPause = { viewModel.player.togglePlayPause() },
                                        onSeekTo = { viewModel.player.seekTo(it) },
                                        onSkipBy = { viewModel.player.skipBy(it) },
                                        onSetSpeed = { viewModel.player.setSpeed(it) },
                                        onToggleLoop = { viewModel.player.toggleLoop() }
                                    )
                                }

                                NavigationBar(
                                    modifier = Modifier
                                        .testTag("bottom_nav_bar")
                                        .windowInsetsPadding(WindowInsets.navigationBars),
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            selectedTab = 0
                                        },
                                        icon = { Icon(Icons.Default.Download, contentDescription = "تحميل الروابط") },
                                        label = { Text("تحميل الروابط") },
                                        modifier = Modifier.testTag("nav_download")
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            selectedTab = 1
                                        },
                                        icon = { Icon(Icons.Default.Search, contentDescription = "البحث بالإنترنت") },
                                        label = { Text("بحث الإنترنت") },
                                        modifier = Modifier.testTag("nav_search")
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            selectedTab = 2
                                        },
                                        icon = { Icon(Icons.Default.Transform, contentDescription = "تحويل الجودات") },
                                        label = { Text("تحويل الجودة") },
                                        modifier = Modifier.testTag("nav_converter")
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 3,
                                        onClick = {
                                            focusManager.clearFocus(force = true)
                                            keyboardController?.hide()
                                            selectedTab = 3
                                        },
                                        icon = { Icon(Icons.Default.FolderSpecial, contentDescription = "محطة الملفات") },
                                        label = { Text("المكتبة") },
                                        modifier = Modifier.testTag("nav_library")
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            androidx.activity.compose.BackHandler(enabled = selectedTab != 0) {
                                selectedTab = 0
                            }

                            when (selectedTab) {
                                0 -> DownloadScreen(viewModel = viewModel)
                                1 -> SearchScreen(
                                    viewModel = viewModel,
                                    onNavigateToDownloader = { url ->
                                        viewModel.updateDownloadUrl(url)
                                        viewModel.analyzeUrl()
                                        selectedTab = 0
                                    }
                                )
                                2 -> SpeedConverterScreen(viewModel = viewModel)
                                3 -> LibraryScreen(
                                    viewModel = viewModel,
                                    onNavigateToDownloader = { selectedTab = 0 }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
