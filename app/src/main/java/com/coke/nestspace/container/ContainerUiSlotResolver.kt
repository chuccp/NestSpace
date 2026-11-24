package com.coke.nestspace.container

/**
 * Selects the container a UI tool should inspect when the caller did not name
 * a package, instance, process or slot.
 *
 * The focused host wins because it is the page the user is looking at. A
 * mirrored slot comes next because its host task is intentionally moved to
 * the back. Foreground process affinity and the last allocation are fallbacks
 * for scenes where Android does not expose a focused host task.
 */
internal fun chooseContainerUiSlot(
    focusedSlot: Int?,
    mirroredSlot: Int?,
    foregroundSlot: Int?,
    lastSlot: Int?,
    liveSlots: Set<Int>
): Int? = listOf(focusedSlot, mirroredSlot, foregroundSlot, lastSlot)
    .firstOrNull { it != null && it in liveSlots }
