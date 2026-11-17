package com.coke.nestspace.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small in-process signal used when a container Activity closes and the
 * launcher should return to its desktop tab, regardless of which sub-page was
 * showing before the container was opened.
 */
object DesktopNavigation {
    data class TaskCenterRequest(
        val requestId: Int,
        val taskId: String? = null
    )
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requests: SharedFlow<Unit> = _requests

    fun requestDesktop() {
        _requests.tryEmit(Unit)
    }

    private val _taskCenterRequests = MutableStateFlow(TaskCenterRequest(0))
    val taskCenterRequests: StateFlow<TaskCenterRequest> = _taskCenterRequests

    fun requestTaskCenter(taskId: String? = null) {
        _taskCenterRequests.value = TaskCenterRequest(
            requestId = _taskCenterRequests.value.requestId + 1,
            taskId = taskId
        )
    }
}
