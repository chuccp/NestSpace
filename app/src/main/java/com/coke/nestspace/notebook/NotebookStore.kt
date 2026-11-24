package com.coke.nestspace.notebook

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class NotebookImage(
    val fileName: String,
    val label: String = ""
)

data class NotebookNote(
    val id: String,
    val title: String,
    val body: String,
    val images: List<NotebookImage> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
) {
    val isEmpty: Boolean
        get() = title.isBlank() && body.isBlank() && images.isEmpty()
}

object NotebookCodec {
    fun encode(notes: List<NotebookNote>): String = JSONObject()
        .put(
            "notes",
            JSONArray().apply {
                notes.forEach { note ->
                    put(
                        JSONObject()
                            .put("id", note.id)
                            .put("title", note.title)
                            .put("body", note.body)
                            .put("created_at", note.createdAt)
                            .put("updated_at", note.updatedAt)
                            .put(
                                "images",
                                JSONArray().apply {
                                    note.images.forEach { image ->
                                        put(
                                            JSONObject()
                                                .put("file", image.fileName)
                                                .put("label", image.label)
                                        )
                                    }
                                }
                            )
                    )
                }
            }
        )
        .toString()

    fun decode(raw: String): List<NotebookNote> = runCatching {
        val notes = JSONObject(raw).optJSONArray("notes") ?: return emptyList()
        buildList {
            for (index in 0 until notes.length()) {
                val json = notes.optJSONObject(index) ?: continue
                val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                val images = json.optJSONArray("images") ?: JSONArray()
                add(
                    NotebookNote(
                        id = id,
                        title = json.optString("title"),
                        body = json.optString("body"),
                        images = buildList {
                            for (imageIndex in 0 until images.length()) {
                                val image = images.optJSONObject(imageIndex) ?: continue
                                val fileName = image.optString("file")
                                if (fileName.isNotBlank()) {
                                    add(
                                        NotebookImage(
                                            fileName = fileName,
                                            label = image.optString("label")
                                        )
                                    )
                                }
                            }
                        },
                        createdAt = json.optLong("created_at"),
                        updatedAt = json.optLong("updated_at")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

class NotebookStore(context: Context) {
    private val appContext = context.applicationContext
    private val notebookDir = File(appContext.filesDir, "notebook")
    private val imagesDir = File(notebookDir, "images")
    private val notesFile = File(notebookDir, "notes.json")

    @Synchronized
    fun load(): List<NotebookNote> {
        if (!notesFile.isFile) return emptyList()
        return NotebookCodec.decode(notesFile.readText())
            .filter { note -> note.images.all { imageFile(it.fileName).isFile } }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun save(note: NotebookNote): NotebookNote {
        val saved = note.copy(updatedAt = System.currentTimeMillis())
        val next = load()
            .filterNot { it.id == saved.id }
            .plus(saved)
            .sortedByDescending { it.updatedAt }
        write(next)
        return saved
    }

    @Synchronized
    fun delete(noteId: String) {
        val next = load().filterNot { it.id == noteId }
        write(next)
        val noteImages = File(imagesDir, noteId)
        noteImages.listFiles()?.forEach(File::delete)
        noteImages.delete()
    }

    @Synchronized
    fun importImage(noteId: String, uri: Uri): NotebookImage? {
        val input = appContext.contentResolver.openInputStream(uri) ?: return null
        val targetDir = File(imagesDir, noteId).apply { mkdirs() }
        val extension = extensionFor(
            appContext.contentResolver.getType(uri)
                ?: uri.lastPathSegment.orEmpty()
        )
        val file = File(targetDir, "image-${UUID.randomUUID()}.$extension")
        val copied = runCatching {
            input.use { source ->
                file.outputStream().use { target -> source.copyTo(target) }
            }
            file.isFile && file.length() > 0
        }.getOrDefault(false)
        if (!copied) {
            file.delete()
            return null
        }
        return NotebookImage(fileName = file.relativeTo(imagesDir).invariantSeparatorsPath)
    }

    fun imageFile(fileName: String): File = File(imagesDir, fileName)

    fun deleteImage(image: NotebookImage) {
        imageFile(image.fileName).delete()
    }

    private fun write(notes: List<NotebookNote>) {
        notebookDir.mkdirs()
        val temp = File(notebookDir, "notes.json.tmp")
        temp.writeText(NotebookCodec.encode(notes))
        if (!temp.renameTo(notesFile)) {
            notesFile.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun extensionFor(value: String): String {
        val lower = value.lowercase()
        return when {
            "png" in lower -> "png"
            "webp" in lower -> "webp"
            "gif" in lower -> "gif"
            else -> "jpg"
        }
    }
}
