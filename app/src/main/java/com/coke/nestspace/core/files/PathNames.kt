package com.coke.nestspace.core.files

/** Converts dynamic identifiers into a single safe filesystem path segment. */
internal fun safePathSegment(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]"), "_")
