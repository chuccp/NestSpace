package com.coke.nestspace.update

/**
 * Version comparison for GitHub release tags.
 *
 * Release tags use SemVer-like values such as `v1.0.7`; local builds may use
 * shorter versions such as `1.0`. Build metadata is ignored and a stable
 * release sorts after a pre-release with the same numeric core.
 */
object AppVersion {
    fun normalize(value: String): String =
        value.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("+")
            .trim()

    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    fun compare(left: String, right: String): Int {
        val leftValue = normalize(left)
        val rightValue = normalize(right)
        val leftCore = leftValue.substringBefore("-")
        val rightCore = rightValue.substringBefore("-")
        val leftParts = leftCore.split(".")
        val rightParts = rightCore.split(".")
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val leftPart = leftParts.getOrElse(index) { "0" }
            val rightPart = rightParts.getOrElse(index) { "0" }
            val comparison = comparePart(leftPart, rightPart)
            if (comparison != 0) return comparison
        }

        val leftPreRelease = leftValue.substringAfter("-", missingDelimiterValue = "")
        val rightPreRelease = rightValue.substringAfter("-", missingDelimiterValue = "")
        return when {
            leftPreRelease.isBlank() && rightPreRelease.isBlank() -> 0
            leftPreRelease.isBlank() -> 1
            rightPreRelease.isBlank() -> -1
            else -> comparePart(leftPreRelease, rightPreRelease)
        }
    }

    private fun comparePart(left: String, right: String): Int {
        val leftNumber = left.toBigIntegerOrNull()
        val rightNumber = right.toBigIntegerOrNull()
        return if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            left.compareTo(right, ignoreCase = true)
        }
    }
}
