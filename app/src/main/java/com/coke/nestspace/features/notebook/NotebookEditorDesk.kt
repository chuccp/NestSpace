package com.coke.nestspace.features.notebook

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coke.nestspace.core.i18n.AppLanguage
import java.io.File
import kotlinx.coroutines.delay

private val DeskInk = Color(0xFFF7F4FB)
private val DeskSurface = Color(0xFFFEFBFF)
private val DeskSurfaceHigh = Color(0xFFEDE8F4)
private val DeskText = Color(0xFF1D1B20)
private val DeskMuted = Color(0xFF625B71)
private val DeskCoral = Color(0xFF6750A4)
private val DeskMint = Color(0xFF3F686B)
private val DeskPink = Color(0xFFA33A5E)
private val DeskYellow = Color(0xFF7D5700)
private val DeskBlue = Color(0xFF4B5B92)

@Composable
internal fun NotebookEditorDesk(
    initial: NotebookNote,
    store: NotebookStore,
    onSave: (NotebookNote) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = deskAccent(initial.id)
    var title by rememberSaveable(initial.id) { mutableStateOf(initial.title) }
    var body by rememberSaveable(initial.id) { mutableStateOf(initial.body) }
    var images by remember(initial.id) { mutableStateOf(initial.images) }
    var saved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        val added = uris.mapNotNull { store.importImage(initial.id, it) }
        if (added.isNotEmpty()) {
            images = images + added
            saved = false
        }
    }

    fun currentNote(): NotebookNote = initial.copy(
        title = title.trim(),
        body = body,
        images = images
    )

    fun saveNow() {
        val note = currentNote()
        if (note.isEmpty) return
        onSave(note)
        saved = true
    }

    LaunchedEffect(title, body, images) {
        delay(450)
        val note = currentNote()
        if (!note.isEmpty) {
            onSave(note)
            saved = true
        }
    }
    LaunchedEffect(title, body, images) {
        NotebookScreenReaderBridge.publish(
            NotebookReaderState(
                activityLabel = AppLanguage.tr("记事本", "Notebook"),
                contentTitle = title.ifBlank {
                    AppLanguage.tr("未命名笔记", "Untitled note")
                },
                contentBody = body.ifBlank {
                    AppLanguage.tr("暂无正文", "No text yet")
                },
                images = images.mapIndexed { index, image ->
                    AppLanguage.tr(
                        "第 ${index + 1} 张笔记图片" +
                            image.label.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty(),
                        "Notebook image ${index + 1}" +
                            image.label.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                    )
                },
                actions = listOf(
                    AppLanguage.tr("添加图片", "Add image"),
                    AppLanguage.tr("保存笔记", "Save note"),
                    AppLanguage.tr("删除笔记", "Delete note"),
                    AppLanguage.tr("返回笔记列表", "Back to notes")
                )
            )
        )
    }

    Scaffold(containerColor = DeskInk) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.30f),
                            Color(0xFFF1EBF8),
                            DeskInk
                        )
                    )
                )
                .padding(padding)
        ) {
            Box(
                Modifier
                    .size(520.dp)
                    .offset(x = 290.dp, y = (-300).dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                accent.copy(alpha = 0.22f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DeskButton(
                        description = AppLanguage.tr("返回笔记列表", "Back to notes"),
                        onClick = {
                            saveNow()
                            onClose()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = DeskText
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("NOTE", "NOTE"),
                            color = accent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            if (saved) {
                                AppLanguage.tr("已自动保存", "Autosaved")
                            } else {
                                AppLanguage.tr("正在编辑", "Editing")
                            },
                            color = DeskMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    DeskButton(
                        description = AppLanguage.tr("插入图片", "Insert image"),
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = accent
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    DeskButton(
                        description = AppLanguage.tr("保存笔记", "Save note"),
                        onClick = {
                            saveNow()
                            saved = true
                        }
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = null,
                            tint = DeskMint
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    DeskButton(
                        description = AppLanguage.tr("删除笔记", "Delete note"),
                        onClick = { confirmDelete = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = DeskPink
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(34.dp),
                    color = DeskSurface.copy(alpha = 0.94f),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .border(
                            1.dp,
                                DeskMuted.copy(alpha = 0.16f),
                            RoundedCornerShape(34.dp)
                        )
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = accent.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    deskDate(initial.createdAt),
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 5.dp
                                    ),
                                    color = accent,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                AppLanguage.tr(
                                    "${body.length} 字 · ${images.size} 图",
                                    "${body.length} chars · ${images.size} images"
                                ),
                                color = DeskMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        BasicTextField(
                            value = title,
                            onValueChange = {
                                title = it
                                saved = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.displaySmall.copy(
                                color = DeskText,
                                fontWeight = FontWeight.Black
                            ),
                            cursorBrush = SolidColor(accent),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box {
                                    if (title.isBlank()) {
                                        Text(
                                            AppLanguage.tr("无标题", "Untitled"),
                                            color = DeskMuted.copy(alpha = 0.55f),
                                            style = MaterialTheme.typography.displaySmall,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DeskMuted.copy(alpha = 0.14f))
                        )
                        Spacer(Modifier.height(18.dp))
                        BasicTextField(
                            value = body,
                            onValueChange = {
                                body = it
                                saved = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = DeskText,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.55f
                            ),
                            cursorBrush = SolidColor(accent),
                            decorationBox = { inner ->
                                Box {
                                    if (body.isBlank()) {
                                        Text(
                                            AppLanguage.tr(
                                                "写下此刻想到的事…",
                                                "Write down what is on your mind…"
                                            ),
                                            color = DeskMuted,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        if (images.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            LazyRow(
                                modifier = Modifier.height(144.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(images, key = { it.fileName }) { image ->
                                    Box(
                                        Modifier
                                            .size(width = 212.dp, height = 144.dp)
                                            .clip(RoundedCornerShape(24.dp))
                                            .border(
                                                1.dp,
                                                DeskMuted.copy(alpha = 0.16f),
                                                RoundedCornerShape(24.dp)
                                            )
                                    ) {
                                        val bitmap = remember(image.fileName) {
                                            decodeDeskImage(store.imageFile(image.fileName))
                                        }
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = AppLanguage.tr(
                                                    "笔记图片",
                                                    "Notebook image"
                                                ),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.Black.copy(alpha = 0.58f),
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    store.deleteImage(image)
                                                    images = images.filterNot {
                                                        it.fileName == image.fileName
                                                    }
                                                    saved = false
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = AppLanguage.tr(
                                                        "删除这张图片",
                                                        "Delete this image"
                                                    ),
                                                    tint = DeskText
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(AppLanguage.tr("删除笔记？", "Delete note?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "文字和图片都会从这台设备删除，无法恢复。",
                        "The text and images will be permanently deleted from this device."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun DeskButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = DeskSurfaceHigh,
        modifier = Modifier.border(
            1.dp,
            DeskMuted.copy(alpha = 0.16f),
            CircleShape
        )
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(46.dp)
                .semantics { contentDescription = description }
        ) {
            content()
        }
    }
}

private fun deskAccent(id: String): Color {
    val palette = listOf(
        DeskCoral,
        DeskMint,
        DeskYellow,
        DeskBlue,
        DeskPink
    )
    return palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
}

private fun deskDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val pattern = if (AppLanguage.isChinese()) "M月d日 HH:mm" else "MMM d · HH:mm"
    return java.text.SimpleDateFormat(
        pattern,
        if (AppLanguage.isChinese()) java.util.Locale.CHINA else java.util.Locale.getDefault()
    ).format(java.util.Date(timestamp)).uppercase()
}

private fun decodeDeskImage(file: File): ImageBitmap? = runCatching {
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
