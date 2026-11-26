package com.coke.nestspace.core.time

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun formatShortDateTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
