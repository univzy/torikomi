package univzy.torikomi.ui.screens

/** Human-readable approximate file size, shared across download/queue screens. */
internal fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "~%.1f GB".format(mb / 1024.0) else "~%.0f MB".format(mb)
}
