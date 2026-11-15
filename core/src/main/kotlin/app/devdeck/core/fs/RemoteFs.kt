package app.devdeck.core.fs

/** A file system the app and the agent can browse and edit (SFTP or Nextcloud/WebDAV). */
interface RemoteFs {
    suspend fun list(path: String): List<RemoteEntry>
    suspend fun read(path: String, maxBytes: Long = MAX_READ_BYTES): ByteArray
    suspend fun write(path: String, data: ByteArray)
    suspend fun mkdirs(path: String)
    suspend fun delete(path: String)
    /** Directory to start browsing in. */
    suspend fun home(): String

    companion object { const val MAX_READ_BYTES = 2L * 1024 * 1024 }
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedEpochSeconds: Long?,
)

class FileTooLargeException(path: String, size: Long, max: Long) :
    Exception("$path ist zu groß ($size Bytes, Maximum $max Bytes).")

fun joinPath(dir: String, name: String): String = when {
    name.startsWith("/") -> name
    dir.endsWith("/") -> dir + name
    else -> "$dir/$name"
}

fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

/** Heuristic: treat data containing NUL bytes or invalid UTF-8 as binary. */
fun looksBinary(data: ByteArray): Boolean {
    val sample = data.copyOf(minOf(data.size, 8000))
    if (sample.any { it == 0.toByte() }) return true
    val decoder = Charsets.UTF_8.newDecoder()
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(sample)); false
    } catch (e: java.nio.charset.CharacterCodingException) {
        // A multi-byte char cut off at the sample boundary is not binary.
        sample.size == data.size || runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(sample, 0, sample.size - 4))
        }.isFailure
    }
}
