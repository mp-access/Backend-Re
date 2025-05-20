package ch.uzh.ifi.access

object Util {

    const val KILOBYTE = 1024L
    const val MEGABYTE = KILOBYTE * 1024L
    const val GIGABYTE = MEGABYTE * 1024L

    fun bytesToString(bytes: Long): String {
        return when {
            bytes < KILOBYTE -> "$bytes B"
            bytes < MEGABYTE -> String.format("%.2f KB", bytes / KILOBYTE)
            bytes < GIGABYTE -> String.format("%.2f MB", bytes / MEGABYTE)
            else -> String.format("%.2f GB", bytes / GIGABYTE)
        }
    }
}
