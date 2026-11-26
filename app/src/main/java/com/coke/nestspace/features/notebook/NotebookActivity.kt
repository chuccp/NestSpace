package com.coke.nestspace.features.notebook

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import com.coke.nestspace.features.assistant.FloatingDotOverlay
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.design.theme.VirtualTheme
import java.io.File
import java.util.UUID

private val NotebookInk = Color(0xFFF7F4FB)
private val NotebookSurface = Color(0xFFFEFBFF)
private val NotebookSurfaceHigh = Color(0xFFEDE8F4)
private val NotebookText = Color(0xFF1D1B20)
private val NotebookMuted = Color(0xFF625B71)
private val NotebookCoral = Color(0xFF6750A4)
private val NotebookMint = Color(0xFF3F686B)
private val NotebookYellow = Color(0xFF7D5700)
private val NotebookBlue = Color(0xFF4B5B92)
private val NotebookPink = Color(0xFFA33A5E)

class NotebookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            VirtualTheme {
                NotebookScreen(onClose = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NotebookScreenReaderBridge.setActive(true)
        FloatingDotOverlay.setContainerSurfaceVisible(this, "notebook", true)
    }

    override fun onStop() {
        NotebookScreenReaderBridge.setActive(false)
        FloatingDotOverlay.setContainerSurfaceVisible(this, "notebook", false)
        super.onStop()
    }
}

@Composable
private fun NotebookScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { NotebookStore(context) }
    var notes by remember { mutableStateOf(store.load()) }
    var editingNote by remember { mutableStateOf<NotebookNote?>(null) }

    BackHandler {
        if (editingNote == null) onClose() else {
            editingNote = null
            notes = store.load()
        }
    }

    val note = editingNote
    if (note == null) {
        NotebookHome(
            notes = notes,
            store = store,
            onBack = onClose,
            onNew = {
                val now = System.currentTimeMillis()
                editingNote = NotebookNote(
                    id = UUID.randomUUID().toString(),
                    title = "",
                    body = "",
                    createdAt = now,
                    updatedAt = now
                )
            },
            onOpen = { editingNote = it }
        )
    } else {
        NotebookEditorDesk(
            initial = note,
            store = store,
            onSave = { updated ->
                store.save(updated)
                notes = store.load()
            },
            onClose = {
                editingNote = null
                notes = store.load()
            },
            onDelete = {
                store.delete(note.id)
                editingNote = null
                notes = store.load()
            }
        )
    }
}

@Composable
private fun NotebookHome(
    notes: List<NotebookNote>,
    store: NotebookStore,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (NotebookNote) -> Unit
) {
    LaunchedEffect(notes) {
        NotebookScreenReaderBridge.publish(
            NotebookReaderState(
                activityLabel = AppLanguage.tr("记事本", "Notebook"),
                contentTitle = if (notes.isEmpty()) {
                    AppLanguage.tr("还没有笔记", "No notes yet")
                } else {
                    AppLanguage.tr("笔记列表，共 ${notes.size} 篇", "Notes: ${notes.size}")
                },
                contentBody = notes.joinToString("\n") { note ->
                    buildString {
                        append(note.title.ifBlank {
                            AppLanguage.tr("未命名笔记", "Untitled note")
                        })
                        if (note.body.isNotBlank()) {
                            append("：")
                            append(note.body.replace('\n', ' ').take(160))
                        }
                        if (note.images.isNotEmpty()) {
                            append("（${note.images.size} 张图片）")
                        }
                    }
                },
                actions = listOf(
                    AppLanguage.tr("新建笔记", "New note"),
                    AppLanguage.tr("返回桌面", "Back to desktop")
                )
            )
        )
    }

    Scaffold(containerColor = NotebookInk) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFF2ECFA),
                            NotebookInk,
                            Color(0xFFFBF8FF)
                        )
                    )
                )
                .padding(padding)
        ) {
            AmbientOrb(
                color = NotebookCoral,
                modifier = Modifier
                    .size(420.dp)
                    .offset(x = 380.dp, y = (-230).dp)
            )
            AmbientOrb(
                color = NotebookMint,
                modifier = Modifier
                    .size(330.dp)
                    .offset(x = (-190).dp, y = 760.dp)
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(
                        description = AppLanguage.tr("返回桌面", "Back to desktop"),
                        onClick = onBack
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = NotebookText
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("MEMO", "MEMO"),
                            color = NotebookCoral,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            AppLanguage.tr("灵感与记录", "Ideas, notes & more"),
                            color = NotebookText,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = NotebookCoral
                    ) {
                        IconButton(onClick = onNew, modifier = Modifier.size(58.dp)) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = AppLanguage.tr("新建笔记", "New note"),
                                tint = NotebookInk
                            )
                        }
                    }
                }

                if (notes.isEmpty()) {
                    EmptyNotebook(onNew)
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                AppLanguage.tr("全部笔记", "All notes"),
                                color = NotebookText,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                AppLanguage.tr(
                                    "${notes.size} 篇 · 最近编辑",
                                    "${notes.size} notes · recently edited"
                                ),
                                color = NotebookMuted,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        NoteStat(
                            label = AppLanguage.tr("图片", "Images"),
                            value = notes.sumOf { it.images.size }.toString()
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            top = 10.dp,
                            bottom = 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(notes, key = { it.id }) { note ->
                            NoteTile(
                                note = note,
                                store = store,
                                onClick = { onOpen(note) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNotebook(onNew: () -> Unit) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(34.dp),
                color = NotebookSurface,
                modifier = Modifier
                    .size(112.dp)
                    .border(
                        1.dp,
                        NotebookMuted.copy(alpha = 0.16f),
                        RoundedCornerShape(34.dp)
                    )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.NoteAdd,
                        contentDescription = null,
                        tint = NotebookYellow,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                AppLanguage.tr("先写点什么", "Start with a thought"),
                color = NotebookText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(8.dp))
            Text(
                AppLanguage.tr(
                    "文字、图片，以及可被读屏理解的页面。",
                    "Text, images, and content the screen reader can understand."
                ),
                color = NotebookMuted,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = NotebookCoral
            ) {
                TextButton(onClick = onNew) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        tint = NotebookInk
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        AppLanguage.tr("开始记录", "Start writing"),
                        color = NotebookInk,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteTile(
    note: NotebookNote,
    store: NotebookStore,
    onClick: () -> Unit
) {
    val accent = notebookAccent(note.id)
    val preview = remember(note.images.firstOrNull()?.fileName) {
        note.images.firstOrNull()?.let {
            decodeNotebookImage(store.imageFile(it.fileName))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.16f), NotebookSurface)
                )
            )
            .border(
                1.dp,
                NotebookMuted.copy(alpha = 0.16f),
                RoundedCornerShape(26.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = AppLanguage.tr(
                            "笔记封面图片",
                            "Note cover image"
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.NoteAdd,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formattedNoteTime(note.updatedAt).uppercase(),
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (note.images.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = accent.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 3.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = accent
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${note.images.size}",
                                    color = accent,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    note.title.ifBlank {
                        AppLanguage.tr("未命名笔记", "Untitled note")
                    },
                    color = NotebookText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (note.body.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        note.body.replace('\n', ' '),
                        color = NotebookMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .width(4.dp)
                    .height(38.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@Composable
private fun NoteStat(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NotebookSurface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                value,
                color = NotebookText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black
            )
            Text(
                label,
                color = NotebookMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun GlassButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = NotebookSurfaceHigh,
        modifier = Modifier.border(
            1.dp,
            NotebookMuted.copy(alpha = 0.16f),
            CircleShape
        )
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(46.dp)
                .semantics { this.contentDescription = description }
        ) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AmbientOrb(
    color: Color,
    modifier: Modifier
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        color.copy(alpha = 0.22f),
                        color.copy(alpha = 0.05f),
                        Color.Transparent
                    )
                )
            )
    )
}

private fun notebookAccent(id: String): Color {
    val palette = listOf(
        NotebookCoral,
        NotebookMint,
        NotebookYellow,
        NotebookBlue,
        NotebookPink
    )
    return palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
}

private fun formattedNoteTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val pattern = if (AppLanguage.isChinese()) "M月d日 HH:mm" else "MMM d · HH:mm"
    return java.text.SimpleDateFormat(
        pattern,
        if (AppLanguage.isChinese()) java.util.Locale.CHINA else java.util.Locale.getDefault()
    ).format(java.util.Date(timestamp))
}

private fun decodeNotebookImage(file: File): ImageBitmap? = runCatching {
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1800) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )?.asImageBitmap()
}.getOrNull()
