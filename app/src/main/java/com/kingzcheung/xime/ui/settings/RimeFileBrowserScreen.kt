package com.kingzcheung.xime.ui.settings

import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.settings.ExportResult
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.RimeUserFileSync
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SyncManager
import com.kingzcheung.xime.settings.UserConfigDir
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.kingzcheung.xime.settings.formatByteSize

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long,
    /**
     * 非空表示条目属于用户可见配置目录 `/storage/emulated/0/rime/xime/`（`UserConfigDir` 后端，
     * 不走私有 rime 目录的 File API）：空字符串是该目录的虚拟入口，其余值为目录内的文件名。
     */
    val userConfigName: String? = null
)

/** 用户可见配置目录的虚拟入口标识。 */
private const val USER_CONFIG_ROOT = ""

/** 虚拟入口在列表中的展示名。 */
private const val USER_CONFIG_ROOT_LABEL = "配置目录（内部存储/rime/xime）"

/**
 * 编辑器数据源：把 `File`（私有 rime 目录）与 [UserConfigDir]（`/storage/emulated/0/rime/xime/`）
 * 两种后端统一到同一套编辑 UI，避免为了新目录复制一份编辑器实现。
 */
private class EditorSource(
    val name: String,
    val read: () -> String,
    val size: () -> Long,
    val modified: () -> Long,
    val write: (String) -> Boolean,
    val delete: () -> Boolean
)

private fun fileEditorSource(file: File) = EditorSource(
    name = file.name,
    read = { file.readText(Charsets.UTF_8) },
    size = { file.length() },
    modified = { file.lastModified() },
    write = { text -> runCatching { file.writeText(text, Charsets.UTF_8) }.isSuccess },
    delete = { runCatching { file.delete() }.getOrDefault(false) }
)

private fun userConfigEditorSource(context: android.content.Context, name: String) = EditorSource(
    name = name,
    read = { UserConfigDir.readText(context, name).orEmpty() },
    size = { UserConfigDir.stamp(context, name)?.second ?: 0L },
    // MediaStore 的 DATE_MODIFIED 单位是秒，UI 按毫秒格式化
    modified = { (UserConfigDir.stamp(context, name)?.first ?: 0L) * 1000L },
    write = { text -> UserConfigDir.writeText(context, name, text) },
    delete = { UserConfigDir.delete(context, name) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RimeFileBrowserContent(
    onBack: () -> Unit,
    /**
     * 是否直接落在「用户可见配置目录」（`/storage/emulated/0/rime/xime/`）。
     *
     * 「打开配置目录」这个入口的字面承诺就是「打开配置目录」，却把用户丢在私有
     * `files/rime/` 根目录、还要再点一层虚拟入口才能看到 `xime.yaml`——多一次点击，
     * 而且私有目录里的文件对用户是可改但会被覆盖的中间产物，容易改错地方。
     * 现在从设置页进来直接定位到配置文件所在目录。
     *
     * 权限未授权/释放失败时自动退回私有目录（`browsingUserConfig=false`），
     * 否则点进来是个空目录，比多一层入口更让人困惑。
     */
    startInUserConfigDir: Boolean = false,
) {
    val context = LocalContext.current
    val rootDir = remember { SchemaManager.getRimeDir(context) }
    var currentDir by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<FileEntry>()) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    // 用户可见配置目录 /storage/emulated/0/rime/xime/ 的浏览态与正在编辑的文件名。
    // 入口承诺直达配置目录，因此有权限时初始即为 true。
    val canUseUserConfig = UserConfigDir.releaseState == UserConfigDir.ReleaseState.SUCCESS
    var browsingUserConfig by remember { mutableStateOf(startInUserConfigDir && canUseUserConfig) }
    // releaseState 是 Compose 可观察状态，且授权弹窗由 MainActivity 挂在设置页**之上**：
    // 用户完全可能在本页已经组合之后才授权成功。若只靠上面的 remember 初值，
    // 状态从 PERMISSION_REQUIRED 翻到 SUCCESS 时本页不会重算，用户被留在了私有
    // files/rime 目录——正是「打开配置目录却看不到配置」的老问题换个触发时机重演。
    //
    // 只在「直达模式 + 尚未进入配置目录 + 权限刚到」时补一次跳转：
    // 用 tookEffect 守住，避免用户主动返回私有目录后被再次弹回。
    var autoEnteredUserConfig by remember { mutableStateOf(false) }
    LaunchedEffect(startInUserConfigDir, canUseUserConfig) {
        if (startInUserConfigDir && canUseUserConfig && !autoEnteredUserConfig) {
            autoEnteredUserConfig = true
            browsingUserConfig = true
        }
    }
    var editingUserConfig by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showUserConfigDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<ExportResult?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        entries = if (browsingUserConfig) {
            UserConfigDir.list(context).map {
                FileEntry(
                    file = File(rootDir, it.name),
                    isDirectory = false,
                    name = it.name,
                    size = it.size,
                    // MediaStore 的 DATE_MODIFIED 单位是秒，UI 按毫秒格式化
                    lastModified = it.lastModified * 1000L,
                    userConfigName = it.name
                )
            }
        } else {
            currentDir.listFiles()
                ?.map { FileEntry(it, it.isDirectory, it.name, it.length(), it.lastModified()) }
                ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()
        }
    }

    fun deleteFile(file: File) {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        showDeleteDialog = null
        refresh()
        Toast.makeText(context, "已删除: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    fun deleteUserConfigFile(name: String) {
        showUserConfigDeleteDialog = null
        val ok = UserConfigDir.delete(context, name)
        refresh()
        Toast.makeText(
            context,
            if (ok) "已删除: $name" else "删除失败: $name",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(currentDir, browsingUserConfig) { refresh() }

    // 展示项：位于 rime 根目录时，在列表最前面插入用户可见配置目录的虚拟入口。
    // 入口指向 /storage/emulated/0/rime/xime/，那里才是当前生效的用户配置所在（读取优先级最高），
    // 避免用户点进来只看到私有目录、以为配置没生效。仅在释放确实成功时才给出入口，
    // 否则点进去是个空目录，比没有入口更让人困惑。
    val userConfigReady = UserConfigDir.releaseState == UserConfigDir.ReleaseState.SUCCESS
    val displayEntries = if (!browsingUserConfig && currentDir == rootDir && userConfigReady) {
        listOf(
            FileEntry(
                file = rootDir,
                isDirectory = true,
                name = USER_CONFIG_ROOT_LABEL,
                size = 0L,
                // 虚拟入口没有真实时间戳，FileEntryRow 对它显示路径而非日期
                lastModified = 0L,
                userConfigName = USER_CONFIG_ROOT
            )
        ) + entries
    } else {
        entries
    }

    if (editingUserConfig != null) {
        YamlEditor(
            source = userConfigEditorSource(context, editingUserConfig!!),
            onDeleted = { editingUserConfig = null },
            onBack = { editingUserConfig = null }
        )
        return
    }

    if (viewingFile != null) {
        YamlEditor(
            source = fileEditorSource(viewingFile!!),
            onDeleted = { viewingFile = null },
            onBack = { viewingFile = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (browsingUserConfig) "配置目录" else "文件管理器",
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (browsingUserConfig) {
                            Text(UserConfigDir.DISPLAY_PATH,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        } else {
                            val rel = if (currentDir == rootDir) "" else
                                currentDir.absolutePath.removePrefix(rootDir.absolutePath)
                            if (rel.isNotEmpty()) {
                                Text(rel, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                navigationIcon = {
                    if (browsingUserConfig) {
                        // 直接进入配置目录时没有「上一层」可退，返回应回设置页；
                        // 从私有目录点虚拟入口进来的，则退回私有目录列表。
                        IconButton(
                            onClick = {
                                if (startInUserConfigDir) onBack() else browsingUserConfig = false
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (startInUserConfigDir) "返回" else "上级目录",
                            )
                        }
                    } else if (currentDir == rootDir) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上级目录")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        browsingUserConfig = false
                        currentDir = rootDir
                    }) {
                        Icon(Icons.Default.Home, contentDescription = "根目录")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.IosShare, contentDescription = "导出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (displayEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center) {
                Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(
                    displayEntries,
                    key = { (if (browsingUserConfig) "user-config/" else currentDir.absolutePath + "/") + it.name }
                ) { entry ->
                    val ucName = entry.userConfigName
                    FileEntryRow(
                        entry = entry,
                        onView = {
                            when {
                                // 虚拟入口：进入用户可见配置目录 /storage/emulated/0/rime/xime/
                                ucName == USER_CONFIG_ROOT -> browsingUserConfig = true
                                ucName != null ->
                                    if (isBinaryName(entry.name)) {
                                        Toast.makeText(context, "不支持打开二进制文件", Toast.LENGTH_SHORT).show()
                                    } else {
                                        editingUserConfig = ucName
                                    }
                                entry.isDirectory -> currentDir = entry.file
                                isBinaryName(entry.name) ->
                                    Toast.makeText(context, "不支持打开二进制文件", Toast.LENGTH_SHORT).show()
                                else -> viewingFile = entry.file
                            }
                        },
                        onDelete = {
                            when {
                                ucName != null && ucName != USER_CONFIG_ROOT ->
                                    showUserConfigDeleteDialog = ucName
                                ucName == null -> showDeleteDialog = entry.file
                            }
                        },
                        onShare = {
                            // 可见目录里的文件由系统文件 App 直接分享更合适，这里不提供入口
                            if (ucName == null) {
                                RimeExportManager.shareSingleFile(context, entry.file)
                            }
                        },
                        showShare = ucName == null
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    showUserConfigDeleteDialog?.let { name ->
        AlertDialog(
            onDismissRequest = { showUserConfigDeleteDialog = null },
            title = { Text("删除文件") },
            text = { Text("确定删除「$name」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { deleteUserConfigFile(name) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUserConfigDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    showDeleteDialog?.let { file ->
        val isDir = file.isDirectory
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(if (isDir) "删除目录" else "删除文件") },
            text = { Text("确定删除「${file.name}」？${if (isDir) "\n目录内的所有内容将被删除。" else ""}") },
            confirmButton = {
                TextButton(onClick = { deleteFile(file) }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出 Rime 配置") },
            text = {
                Text(
                    "导出整机完整快照（设置与补丁、方案本体与资源、插件包、自造词快照）为 zip，保存到下载目录。" +
                        "与云备份是同一个包；不含编译产物 build/ 与用户词典库 *.userdb。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportInProgress = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                // 与云备份同构：导出前尽力刷新自造词快照（引擎未就绪则用现有快照）
                                SyncManager.syncNow(context)
                                RimeExportManager.exportArchive(context)
                            }
                            exportInProgress = false
                            result.fold(
                                onSuccess = { exportResult ->
                                    val msg = if (exportResult.savedToDownloads)
                                        "已保存到 Downloads/${exportResult.fileName}"
                                    else
                                        "导出完成"
                                    snackbarHostState.showSnackbar(msg)
                                },
                                onFailure = { error ->
                                    snackbarHostState.showSnackbar("导出失败: ${error.message}")
                                }
                            )
                        }
                    },
                    enabled = !exportInProgress
                ) {
                    Text(if (exportInProgress) "导出中..." else "开始导出")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    enabled = !exportInProgress
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("导出中") },
            text = { Text("正在打包 Rime 配置…") },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlEditor(source: EditorSource, onDeleted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var isModified by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var textLength by remember { mutableIntStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // 元信息只取一次：MediaStore 后端的 size()/modified() 走 ContentResolver 查询，
    // 放在组合期反复调用代价过高
    var meta by remember(source.name) { mutableStateOf(source.size() to source.modified()) }

    val initialContent = remember(source.name) {
        val text = runCatching { source.read() }.getOrDefault("")
        if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    fun save() {
        val content = editor?.text?.toString() ?: return
        if (source.write(content)) {
            isModified = false
            meta = source.size() to source.modified()
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除文件") },
            text = { Text("确定删除「${source.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val ok = source.delete()
                    Toast.makeText(
                        context,
                        if (ok) "已删除: ${source.name}" else "删除失败: ${source.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    onDeleted()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    val textmateReady = remember {
        try {
            val appCtx = context.applicationContext
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(appCtx.assets))
            val themeReg = ThemeRegistry.getInstance()
            themeReg.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream("textmate/themes/light.json"),
                        "light.json", null
                    ), "Light"
                )
            )
            themeReg.setTheme("Light")
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            true
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(source.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sizeStr = formatByteSize(meta.first)
                        val dateStr = dateFormat.format(Date(meta.second))
                        val flag = if (isModified) " · 未保存" else ""
                        Text("$sizeStr · $dateStr$flag",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isModified) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Default.Check, contentDescription = "保存",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = {
                        isEditing = !isEditing
                        editor?.editable = isEditing
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (isEditing) "预览" else "编辑",
                            tint = if (isEditing) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            AndroidView(
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        editable = isEditing
                        typefaceText = Typeface.MONOSPACE
                        setText(initialContent)
                        setTextSize(12f)
                        tabWidth = 4
                        isLineNumberEnabled = true
                        isWordwrap = false
                        isHighlightCurrentLine = false
                        nonPrintablePaintingFlags = 0
                        getComponent(Magnifier::class.java).setEnabled(false)

                        if (textmateReady) {
                            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                            setEditorLanguage(TextMateLanguage.create("source.yaml", false))
                        }

                        subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                            textLength = text.toString().length
                            isModified = text.toString() != initialContent
                        }

                        editor = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryRow(
    entry: FileEntry,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit = {},
    showShare: Boolean = true
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (entry.userConfigName == USER_CONFIG_ROOT) {
                // 虚拟入口没有真实时间戳，显示它实际指向的路径更有意义
                Text(
                    text = UserConfigDir.DISPLAY_PATH,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row {
                    if (!entry.isDirectory && entry.size > 0) {
                        Text(
                            text = formatByteSize(entry.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = dateFormat.format(Date(entry.lastModified)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            if (!entry.isDirectory) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showShare) {
                        IconButton(onClick = onShare) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

/** Rime 编译产物（二进制），文本编辑器无法打开。 */
private fun isBinaryName(name: String): Boolean =
    name.endsWith(".bin") || name.endsWith(".gram")
