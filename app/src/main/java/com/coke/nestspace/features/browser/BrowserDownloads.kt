package com.coke.nestspace.features.browser

import com.coke.nestspace.agent.task.OperationTaskRegistry
import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONObject

    internal fun BrowserSession.clearDownloadRecord(ownerId: String? = null) {
        synchronized(downloadLock) {
            latestDownloadIds.remove(downloadOwnerKeyRecord(ownerId))
            if (ownerId == null || downloadState?.ownerId == ownerId) {
                downloadState = null
            }
        }
    }

    internal fun BrowserSession.startDownloadRecord(
        url: String,
        ownerId: String? = null,
        taskId: String? = null
    ): BrowserSession.DownloadState {
        val record = BrowserSession.DownloadState(
            id = "download-${System.currentTimeMillis()}-${System.nanoTime()}",
            url = url,
            name = downloadNameRecord(url),
            state = "downloading",
            ownerId = ownerId,
            taskId = taskId
        )
        synchronized(downloadLock) {
            // One assistant keeps one active transfer. Downloads owned by the
            // user or by other sessions remain independent.
            _downloads.value = _downloads.value.filterNot {
                ownerId != null &&
                    it.state == "downloading" &&
                    it.ownerId == ownerId
            }
            latestDownloadIds[downloadOwnerKeyRecord(ownerId)] = record.id
            downloadState = record
            _downloads.value = (listOf(record) + _downloads.value).take(MAX_DOWNLOADS)
        }
        return record
    }

    internal fun BrowserSession.updateDownloadProgressRecord(id: String, downloaded: Long, total: Long) {
        synchronized(downloadLock) {
            val current = _downloads.value.firstOrNull { it.id == id } ?: return
            val updated = current.copy(
                downloadedBytes = downloaded.coerceAtLeast(0L),
                totalBytes = total.coerceAtLeast(-1L)
            )
            replaceDownloadRecord(updated)
            if (downloadState?.id == id) downloadState = updated
        }
    }

    internal fun BrowserSession.finishDownloadRecord(id: String, result: BrowserDownloader.DownloadResult): BrowserSession.DownloadState {
        val current = _downloads.value.firstOrNull { it.id == id }
            ?: return BrowserSession.DownloadState(
                id = id,
                url = result.uri,
                name = result.name,
                state = "done",
                downloadedBytes = result.size,
                totalBytes = result.size,
                path = result.path,
                uri = result.uri,
                size = result.size
            )
        val finished = current.copy(
            name = result.name,
            state = "done",
            downloadedBytes = result.size,
            totalBytes = result.size,
            path = result.path,
            uri = result.uri,
            size = result.size,
            error = null
        )
        synchronized(downloadLock) {
            replaceDownloadRecord(finished)
            if (downloadState?.id == id) downloadState = finished
        }
        return finished
    }

    internal fun BrowserSession.failDownloadRecord(id: String, error: Throwable): BrowserSession.DownloadState {
        val current = _downloads.value.firstOrNull { it.id == id }
            ?: return BrowserSession.DownloadState(
                id = id,
                url = "",
                name = "",
                state = "failed",
                error = error.message ?: error.javaClass.simpleName
            )
        val failed = current.copy(
            state = "failed",
            error = error.message ?: error.javaClass.simpleName
        )
        synchronized(downloadLock) {
            replaceDownloadRecord(failed)
            if (downloadState?.id == id) downloadState = failed
        }
        return failed
    }

    internal suspend fun BrowserSession.downloadAndWait(
        context: Context,
        url: String,
        ownerId: String? = null
    ): BrowserSession.DownloadState {
        val currentJob = currentCoroutineContext()[Job]
            ?: throw CancellationException("Download has no coroutine job")
        val task = requireNotNull(createDownloadTask(ownerId, url).task)
        val cancellation = BrowserDownloader.CancellationSignal()
        val (generation, record) = registerDownload(
            url = url,
            ownerId = ownerId,
            job = currentJob,
            cancellation = cancellation,
            taskId = task.id
        )
        return runDownloadJob(
            context = context,
            url = url,
            ownerId = ownerId,
            generation = generation,
            record = record,
            cancellation = cancellation,
            taskId = task.id
        )
    }

    internal fun BrowserSession.enqueueDownloadRecord(
        context: Context,
        url: String,
        ownerId: String? = null,
        requestId: String? = null,
        requestHash: String? = null,
        parentTaskId: String? = null
    ): BrowserSession.DownloadEnqueueResult {
        val started = createDownloadTask(
            ownerId = ownerId,
            url = url,
            requestId = requestId,
            requestHash = requestHash,
            parentTaskId = parentTaskId
        )
        val task = requireNotNull(started.task)
        if (!started.created) {
            val existing = synchronized(downloadLock) {
                _downloads.value.firstOrNull { it.taskId == task.id }
            }
            return BrowserSession.DownloadEnqueueResult(
                download = existing,
                task = task,
                created = false,
                errorCode = started.errorCode
            )
        }
        val cancellation = BrowserDownloader.CancellationSignal()
        var generation = 0L
        var record: BrowserSession.DownloadState? = null
        lateinit var job: Job
        job = headlessScope.launch(start = CoroutineStart.LAZY) {
            runCatching {
                runDownloadJob(
                    context = context,
                    url = url,
                    ownerId = ownerId,
                    generation = generation,
                    record = checkNotNull(record),
                    cancellation = cancellation,
                    taskId = task.id
                )
            }
        }
        val registered = registerDownload(
            url = url,
            ownerId = ownerId,
            job = job,
            cancellation = cancellation,
            taskId = task.id
        )
        generation = registered.first
        record = registered.second
        job.start()
        return BrowserSession.DownloadEnqueueResult(
            download = registered.second,
            task = task,
            created = true
        )
    }

    internal suspend fun BrowserSession.runDownloadJob(
        context: Context,
        url: String,
        ownerId: String?,
        generation: Long,
        record: BrowserSession.DownloadState,
        cancellation: BrowserDownloader.CancellationSignal,
        taskId: String
    ): BrowserSession.DownloadState {
        return try {
            OperationTaskRegistry.transition(
                taskId,
                OperationTaskRegistry.State.RUNNING,
                "downloading ${downloadNameRecord(url)}"
            )
            val startedAt = System.nanoTime()
            val result = BrowserDownloader.download(
                context = context,
                url = url,
                cancellation = cancellation
            ) { downloaded, total ->
                updateDownloadProgress(record.id, downloaded, total)
                val percent = if (total > 0L) {
                    ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                } else {
                    null
                }
                val elapsedSeconds =
                    ((System.nanoTime() - startedAt) / 1_000_000_000.0)
                        .coerceAtLeast(0.001)
                val bytesPerSecond = (downloaded / elapsedSeconds).toLong()
                val etaSeconds = if (total > 0L && bytesPerSecond > 0L) {
                    val remaining = (total - downloaded).coerceAtLeast(0L)
                    (remaining + bytesPerSecond - 1L) / bytesPerSecond
                } else {
                    null
                }
                OperationTaskRegistry.progress(
                    taskId,
                    percent,
                    "downloading ${downloadNameRecord(url)}",
                    current = downloaded,
                    total = total.takeIf { it > 0L },
                    bytesPerSecond = bytesPerSecond,
                    etaSeconds = etaSeconds
                )
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentDownloadRecord(ownerId, generation)) {
                throw CancellationException("Download replaced")
            }
            val finished = finishDownload(record.id, result)
            OperationTaskRegistry.succeed(
                taskId,
                JSONObject()
                    .put("download_id", finished.id)
                    .put("name", finished.name)
                    .put("path", finished.path.orEmpty())
                    .put("uri", finished.uri.orEmpty())
                    .put("size", finished.size)
                    .toString()
            )
            finished
        } catch (t: Throwable) {
            if (t is CancellationException ||
                !currentCoroutineContext().isActive ||
                !isCurrentDownloadRecord(ownerId, generation)
            ) {
                val reason = OperationTaskRegistry.status(taskId)
                    ?.error
                    ?.takeIf(String::isNotBlank)
                    ?: t.message
                    ?: "Download cancelled"
                updateDownloadStateRecord(
                    record.id,
                    "cancelled",
                    reason
                )
                OperationTaskRegistry.markCancelled(
                    taskId,
                    reason
                )
                throw if (t is CancellationException) {
                    t
                } else {
                    CancellationException("Download replaced").also {
                        it.initCause(t)
                    }
                }
            }
            failDownload(record.id, t)
            OperationTaskRegistry.fail(
                taskId,
                t.message ?: t.javaClass.simpleName
            )
            throw t
        } finally {
            synchronized(downloadTaskLock) {
                if (ownerId != null &&
                    downloadGenerations[ownerId] == generation &&
                    activeDownloadJobs[ownerId]?.cancellation === cancellation
                ) {
                    activeDownloadJobs.remove(ownerId)
                }
            }
        }
    }

    internal fun BrowserSession.cancelLatestDownloadRecord(ownerId: String) {
        val active = synchronized(downloadTaskLock) {
            activeDownloadJobs.remove(ownerId).also {
                if (it != null) {
                    downloadGenerations[ownerId] =
                        (downloadGenerations[ownerId] ?: 0L) + 1L
                }
            }
        }
        latestDownloadRecord(ownerId)?.let { current ->
            updateDownloadStateRecord(current.id, "cancelling", null)
        }
        active?.cancellation?.cancel()
        active?.job?.cancel(CancellationException("Download cancelled"))
    }

    internal fun BrowserSession.updateDownloadStateRecord(
        id: String,
        state: String,
        error: String?
    ) {
        synchronized(downloadLock) {
            val current = _downloads.value.firstOrNull { it.id == id } ?: return
            val updated = current.copy(state = state, error = error)
            replaceDownloadRecord(updated)
            if (downloadState?.id == id) downloadState = updated
        }
    }

    internal fun BrowserSession.deleteDownloadRecord(context: Context, id: String): Boolean {
        val target = synchronized(downloadLock) {
            val item = _downloads.value.firstOrNull { it.id == id } ?: return false
            if (item.state == "downloading") return false
            _downloads.value = _downloads.value.filterNot { it.id == id }
            if (downloadState?.id == id) downloadState = null
            latestDownloadIds.entries.removeAll { it.value == id }
            item
        }

        target.uri?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                when (uri.scheme?.lowercase()) {
                    "content" -> context.contentResolver.delete(uri, null, null) > 0
                    "file" -> uri.path?.let { File(it).delete() } == true
                    else -> false
                }
            }
        }
        target.path?.let { path ->
            runCatching { File(path).delete() }
        }
        return true
    }

internal fun BrowserSession.latestDownloadRecord(
    ownerId: String? = null
): BrowserSession.DownloadState? = downloadStatusRecord(ownerId = ownerId)

internal fun BrowserSession.downloadStatusRecord(
    id: String? = null,
    ownerId: String? = null
): BrowserSession.DownloadState? =
    synchronized(downloadLock) {
        val byId = id?.let { wanted ->
            _downloads.value.firstOrNull { it.id == wanted }
        }
        if (byId != null) return@synchronized byId
        if (ownerId != null) {
            latestDownloadIds[ownerId]?.let { latestId ->
                _downloads.value.firstOrNull { it.id == latestId }
            } ?: _downloads.value.firstOrNull {
                it.state == "downloading" && it.ownerId == ownerId
            }
        } else {
            downloadState ?: _downloads.value.firstOrNull {
                it.state == "downloading"
            }
        }
    }

internal fun BrowserSession.replaceDownloadRecord(
    updated: BrowserSession.DownloadState
) {
    _downloads.value = _downloads.value.map { item ->
        if (item.id == updated.id) updated else item
    }
}

internal fun BrowserSession.isCurrentDownloadRecord(
    ownerId: String?,
    generation: Long
): Boolean = ownerId == null || downloadGenerations[ownerId] == generation

internal fun BrowserSession.downloadOwnerKeyRecord(ownerId: String?): String =
    ownerId.orEmpty()

internal fun BrowserSession.downloadNameRecord(url: String): String {
    val fromUrl = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        ?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }
    return fromUrl ?: "download"
}
