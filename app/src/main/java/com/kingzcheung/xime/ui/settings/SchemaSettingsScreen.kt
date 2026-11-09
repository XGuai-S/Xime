package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material.icons.twotone.Computer
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.DriveFolderUpload
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.ConfigDirOpener
import com.kingzcheung.xime.settings.ConfigDirTarget
import com.kingzcheung.xime.settings.LaunchOutcome
import com.kingzcheung.xime.settings.RimeUserFileSync
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.SchemaLocalViewModel
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaSettingsContent(
    onBack: () -> Unit,
    onNavigateToMarket: () -> Unit = {},
    onNavigateToRimeFileBrowser: () -> Unit = {},
    /** 通用文件管理器（rime 根目录起）；[onNavigateToRimeFileBrowser] 也打开 rime 根。 */
    onNavigateToFileManager: () -> Unit = onNavigateToRimeFileBrowser,
    onNavigateToSchemaDictBrowser: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: SchemaSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localViewModel: SchemaLocalViewModel = viewModel()
    val localUiState by localViewModel.uiState.collectAsStateWithLifecycle()
    var tabIndex by remember { mutableStateOf(0) }
    // 「用其他应用打开配置目录」的搜索结果：非空时弹出选择器
    var configDirTargets by remember { mutableStateOf<List<ConfigDirTarget>?>(null) }
    // F6: 从方案市场/导入返回时自动重扫描，新装方案立即出现
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh(); localViewModel.loadLocalPackages() }
    // 切 tab 时刷新对应列表，保持数据一致
    LaunchedEffect(tabIndex) {
        if (tabIndex == 0) viewModel.refresh() else localViewModel.loadLocalPackages()
    }
    // 导入完成后刷新本地包列表
    LaunchedEffect(Unit) {
        viewModel.importCompleted.collect { localViewModel.loadLocalPackages() }
    }
    var showMenu by remember { mutableStateOf(false) }
    var showWirelessSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var wasDownloading by remember { mutableStateOf(false) }
    var showImportWarning by remember { mutableStateOf(false) }
    var dontShowImportWarning by remember { mutableStateOf(false) }
    var pendingImportAction by remember { mutableStateOf<() -> Unit>({}) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (uri in uris) {
            viewModel.importSchemaFile(uri)
        }
    }

    if (showImportWarning) {
        AlertDialog(
            onDismissRequest = {
                showImportWarning = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text("导入方案须知", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text(
                        "使用导入方案功能时，请先去了解 Rime 配置方案以及相关文档（ime.ximei.me），并确保你知道自己正在做什么。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "如果你不知道自己在做什么，请使用默认方案。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontShowImportWarning = !dontShowImportWarning }
                    ) {
                        Checkbox(checked = dontShowImportWarning, onCheckedChange = { dontShowImportWarning = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("不再显示", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontShowImportWarning) SettingsPreferences.setSchemaImportWarningDismissed(context, true)
                        showImportWarning = false
                        pendingImportAction()
                    }
                ) { Text("继续导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportWarning = false }) {
                    Text("取消")
                }
            }
        )
    }

    fun requireImportWarning(action: () -> Unit) {
        if (SettingsPreferences.isSchemaImportWarningDismissed(context)) {
            action()
        } else {
            pendingImportAction = action
            showImportWarning = true
        }
    }

    if (showWirelessSheet) {
        WirelessImportSheet(
            onDismiss = {
                showWirelessSheet = false
                viewModel.refresh()
            },
            onRefresh = { viewModel.refresh() }
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isDownloading) { showUrlDialog = false; urlInput = "" }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text("网络导入", fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column {
                    Text(
                        "输入压缩包下载地址",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "支持 .zip 和 .tar.gz 格式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    var urlFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { urlFocused = it.isFocused }
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                if (urlFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                    ) {
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            enabled = !uiState.isDownloading,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (urlInput.isEmpty() && !urlFocused) {
                                        Text(
                                            "https://example.com/schema.tar.gz",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    if (uiState.isDownloading) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = "使用导入方案功能时，请先了解 Rime 配置方案以及文档（ime.ximei.me），以及确保你知道自己正在做什么。如果你不知道自己在做什么，请使用默认方案。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "正在下载并解压...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wasDownloading = true
                        viewModel.importFromUrl(urlInput)
                    },
                    enabled = urlInput.isNotBlank() && !uiState.isDownloading
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUrlDialog = false; urlInput = "" }
                ) { Text("取消") }
            }
        )
    }

    if (uiState.showUninstallDialog) {
        val packages = uiState.marketPackages
        val selectedIds = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { viewModel.dismissUninstallDialog() },
            title = { Text("卸载方案") },
            text = {
                if (packages.isEmpty()) {
                    Text(
                        "没有可卸载的方案",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column {
                        Text(
                            "选择要卸载的方案：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        packages.forEach { pkg ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (pkg.packageId in selectedIds) {
                                            selectedIds.remove(pkg.packageId)
                                        } else {
                                            selectedIds.add(pkg.packageId)
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Checkbox(
                                    checked = pkg.packageId in selectedIds,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedIds.add(pkg.packageId)
                                        else selectedIds.remove(pkg.packageId)
                                    }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(pkg.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${pkg.schemaCount} 个输入方案 · ${pkg.fileCount} 个文件" +
                                            if (pkg.version.isNotEmpty()) " · v${pkg.version}" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedIds.forEach { viewModel.uninstallPackage(it) }
                    },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Text(
                        "卸载 (${selectedIds.size})",
                        color = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUninstallDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    // Auto-close dialog when download finishes
    LaunchedEffect(uiState.isDownloading) {
        if (wasDownloading && !uiState.isDownloading) {
            showUrlDialog = false
            urlInput = ""
        }
        wasDownloading = uiState.isDownloading
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(localUiState.toastMessage) {
        localUiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            localViewModel.clearToast()
        }
    }

    if (localUiState.conflictPackageId != null) {
        AlertDialog(
            onDismissRequest = { localViewModel.cancelConflictInstall() },
            title = { Text("文件冲突") },
            text = {
                Text("需要先卸载冲突方案：${localUiState.conflictingSchemeIds.joinToString("、")}，是否继续？")
            },
            confirmButton = {
                TextButton(onClick = { localViewModel.confirmInstallWithUninstall() }) {
                    Text("确认卸载并安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { localViewModel.cancelConflictInstall() }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("输入方案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("方案市场") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToMarket()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Storefront, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("浏览器导入") },
                                onClick = {
                                    showMenu = false
                                    requireImportWarning { showWirelessSheet = true }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Computer, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("网络导入") },
                                onClick = {
                                    showMenu = false
                                    requireImportWarning { showUrlDialog = true }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.CloudDownload, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("从文件选择") },
                                onClick = {
                                    showMenu = false
                                    requireImportWarning { importLauncher.launch(arrayOf("*/*")) }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.DriveFolderUpload, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("方案词表") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSchemaDictBrowser()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Description, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            DropdownMenuItem(
                                text = { Text("文件管理器") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToFileManager()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FolderOpen, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("已安装") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("已下载/导入") },
                )
            }

            when (tabIndex) {
                0 -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                text = "已启用",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                            )
                        }

                        val enabledSchemas = uiState.allSchemas.filter { it.schemaId in uiState.enabledSchemas }

                        if (enabledSchemas.isEmpty()) {
                            item {
                                Text(
                                    text = "暂未启用任何方案",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }

                        items(enabledSchemas, key = { it.schemaId }) { schema ->
                            SchemaToggleItem(
                                schema = schema,
                                enabled = true,
                                isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                                isCurrent = schema.schemaId == uiState.currentSchema,
                                onToggle = { viewModel.toggleSchema(schema) },
                                onSelect = { viewModel.selectSchema(schema) },
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "添加输入方案后，需要点击一次「部署方案」进行部署",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val disabledSchemas = uiState.allSchemas.filter { it.schemaId !in uiState.enabledSchemas }

                        if (disabledSchemas.isEmpty()) {
                            item {
                                Text(
                                    text = "没有其他可用方案",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }

                        items(disabledSchemas, key = { it.schemaId }) { schema ->
                            SchemaToggleItem(
                                schema = schema,
                                enabled = false,
                                isCompiled = SchemaManager.isSchemaCompiled(context, schema.schemaId),
                                isCurrent = false,
                                onToggle = { viewModel.toggleSchema(schema) },
                                onSelect = { viewModel.selectSchema(schema) },
                            )
                        }

                        // 配置文件：从「主题与定制」页移来，与「部署方案」相邻——两者是同一件事的
                        // 两半：用系统「文件」App 改完 xime.yaml，回到这里点一次部署才生效。
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsSection(title = "配置文件") {
                                SettingsItem(
                                    icon = Icons.Default.FolderOpen,
                                    title = "打开配置目录",
                                    subtitle = "${SchemaManager.getRimeDir(context).absolutePath} · 方案词典与词库所在目录",
                                    onClick = onNavigateToRimeFileBrowser,
                                    showArrow = true,
                                )
                                // 打开方式选择：枚举手机上所有能打开该目录的应用
                                // （系统「文件」+ 第三方管理器 + 系统文档树选择器兜底）
                                SettingsItem(
                                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                                    title = "用其他应用打开目录",
                                    subtitle = "搜索手机上所有可用的打开方式（第三方文件管理器等）",
                                    onClick = { configDirTargets = ConfigDirOpener.queryTargets(context) },
                                    showArrow = true,
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    Button(
                        onClick = { viewModel.deploySchema() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (uiState.isDeploying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("部署方案")
                    }
                }

                1 -> {
                    val packages = localUiState.packages
                    if (packages.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "暂无本地方案",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { Spacer(Modifier.height(4.dp)) }
                            items(packages, key = { it.packageId }) { item ->
                                LocalPackageCard(
                                    item = item,
                                    installing = localUiState.installingId == item.packageId,
                                    onInstall = { localViewModel.installPackage(item) },
                                    onDelete = { localViewModel.deleteDownloaded(item) },
                                    onUninstall = { localViewModel.uninstall(item) },
                                )
                            }
                            item {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "安装后需切到「已安装」tab 点击「部署方案」生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 「用其他应用打开配置目录」选择弹窗
    configDirTargets?.let { targets ->
        ConfigDirOpenDialog(
            targets = targets,
            onPick = { target ->
                configDirTargets = null
                scope.launch {
                    // 第三方应用无法访问应用私有目录：先把私有 rime 工作目录
                    // （方案词典真正所在）同步到公开 /storage/emulated/0/rime，
                    // 再让第三方打开公开镜像的最新内容
                    withContext(Dispatchers.IO) {
                        runCatching { RimeUserFileSync.sync(context) }
                        // sync() 只管可编辑文本；编译产物与运行状态（build/、*.userdb、
                        // 安装状态）也补进镜像，否则第三方看到的目录内容比内置的少一块
                        runCatching { RimeUserFileSync.mirrorRuntimeFiles(context) }
                    }
                    when (ConfigDirOpener.launch(context, target)) {
                        LaunchOutcome.Located -> Unit
                        // 只打开了应用却没定位：必须如实告知，否则用户以为跳转失败不了
                        LaunchOutcome.OpenedOnly -> Toast.makeText(
                            context,
                            "${target.label} 不支持直接定位，已为你打开它；目录：${RimeUserFileSync.mirrorDir(context).absolutePath}",
                            Toast.LENGTH_LONG,
                        ).show()
                        LaunchOutcome.Failed ->
                            Toast.makeText(context, "无法启动 ${target.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { configDirTargets = null },
            onCopyPath = {
                // 兜底出口：不依赖任何第三方实现，任何管理器都能手动粘贴
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "目录路径",
                        RimeUserFileSync.mirrorDir(context).absolutePath,
                    ),
                )
                Toast.makeText(context, "已复制：${RimeUserFileSync.mirrorDir(context).absolutePath}", Toast.LENGTH_SHORT).show()
                configDirTargets = null
            },
        )
    }
}

@Composable
private fun SchemaToggleItem(
    schema: SchemaMeta,
    enabled: Boolean,
    isCompiled: Boolean,
    isCurrent: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (enabled && isCompiled) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = schema.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    if (isCurrent && isCompiled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "当前方案",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (schema.version.isNotEmpty()) {
                        Text(
                            text = "${schema.version}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (schema.author.isNotEmpty()) {
                        Text(
                            text = schema.author,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (isCompiled) {
                        Text(
                            text = "已编译",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}
