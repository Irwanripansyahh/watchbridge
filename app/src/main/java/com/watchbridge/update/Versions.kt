package com.watchbridge.update

/**
 * True if [candidate] is a newer version than [installed]. Both are "X.Y.Z" (a leading "v",
 * as in release tags, and suffixes like "-debug" are ignored); missing parts count as 0.
 */
internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    val new = versionParts(candidate)
    val old = versionParts(installed)
    for (i in 0 until maxOf(new.size, old.size)) {
        val a = new.getOrElse(i) { 0 }
        val b = old.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun versionParts(version: String): List<Int> =
    version.trim()
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
