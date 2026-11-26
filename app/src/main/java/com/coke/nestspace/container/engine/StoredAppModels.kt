package com.coke.nestspace.container.engine

internal fun StoredApp.defaultInstance(): StoredInstance =
    instances.maxByOrNull { it.lastLaunchedAt } ?: instances.first()

internal fun StoredApp.nextInstanceId(): String {
    val max = instances.mapNotNull {
        it.id.removePrefix("instance-").toIntOrNull()
    }.maxOrNull() ?: 0
    var next = max + 1
    while (instances.any { it.id == "instance-$next" }) next++
    return "instance-$next"
}
