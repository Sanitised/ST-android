package io.github.sanitised.st

internal data class ParsedVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>
)

internal object Versioning {
    fun isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean {
        val current = parseVersion(currentVersion)
        val remote = parseVersion(remoteTag)
        if (current == null || remote == null) {
            return normalizeVersion(remoteTag) != normalizeVersion(currentVersion)
        }
        return compareVersions(remote, current) > 0
    }

    fun parseVersion(raw: String): ParsedVersion? {
        val normalized = normalizeVersion(raw)
        val preBuild = normalized.substringBefore('+')
        val core = preBuild.substringBefore('-')
        val preReleaseRaw = preBuild.substringAfter('-', "")
        val preRelease = if (preReleaseRaw.isBlank()) {
            emptyList()
        } else {
            preReleaseRaw.split('.').filter { it.isNotBlank() }
        }
        val segments = core.split('.')
        if (segments.size != 3) return null

        val major = segments[0].toIntOrNull() ?: return null
        val minor = segments[1].toIntOrNull() ?: return null
        val patch = segments[2].toIntOrNull() ?: return null
        return ParsedVersion(major = major, minor = minor, patch = patch, preRelease = preRelease)
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun compareVersions(left: ParsedVersion, right: ParsedVersion): Int {
        if (left.major != right.major) return left.major.compareTo(right.major)
        if (left.minor != right.minor) return left.minor.compareTo(right.minor)
        if (left.patch != right.patch) return left.patch.compareTo(right.patch)

        if (left.preRelease.isEmpty() && right.preRelease.isEmpty()) return 0
        if (left.preRelease.isEmpty()) return 1
        if (right.preRelease.isEmpty()) return -1

        val shared = minOf(left.preRelease.size, right.preRelease.size)
        for (i in 0 until shared) {
            val cmp = comparePreReleaseIdentifier(left.preRelease[i], right.preRelease[i])
            if (cmp != 0) return cmp
        }

        return left.preRelease.size.compareTo(right.preRelease.size)
    }

    private fun comparePreReleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all { it.isDigit() }
        val rightNumeric = right.all { it.isDigit() }

        return when {
            leftNumeric && rightNumeric -> compareNumericStrings(left, right)
            leftNumeric && !rightNumeric -> -1
            !leftNumeric && rightNumeric -> 1
            else -> left.compareTo(right)
        }
    }

    fun compareNumericStrings(left: String, right: String): Int {
        val leftNorm = left.trimStart('0').ifEmpty { "0" }
        val rightNorm = right.trimStart('0').ifEmpty { "0" }
        if (leftNorm.length != rightNorm.length) {
            return leftNorm.length.compareTo(rightNorm.length)
        }
        return leftNorm.compareTo(rightNorm)
    }
}
