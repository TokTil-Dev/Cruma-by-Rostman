package cruma.bgfx

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal fun File.sha256(): String = inputStream().use { it.digest("SHA-256") }

internal fun File.sha1(): String = inputStream().use { it.sha1() }

internal fun InputStream.sha1(): String = digest("SHA-1")

private fun InputStream.digest(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read > 0) digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
