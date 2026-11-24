package cruma.bgfx

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.time.Instant

object StableSourceTree {
    fun sync(source: File, destination: File) {
        val from = source.toPath().toAbsolutePath().normalize()
        val to = destination.toPath().toAbsolutePath().normalize()
        require(!from.startsWith(to) && !to.startsWith(from)) { "Source trees must not overlap" }
        require(Files.isDirectory(from, NOFOLLOW_LINKS)) { "Missing prepared source tree: $from" }
        val entries = Files.walk(from).use { paths ->
            paths.filter { it != from }.toList().associateBy { from.relativize(it) }
        }
        if (Files.exists(to, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(to, NOFOLLOW_LINKS)) { "Not a source directory: $to" }
            Files.walk(to).use { paths ->
                paths.filter { it != to }.sorted(Comparator.reverseOrder()).forEach { existing ->
                    val prepared = entries[to.relativize(existing)]
                    if (prepared == null || Files.isDirectory(existing, NOFOLLOW_LINKS) != Files.isDirectory(prepared, NOFOLLOW_LINKS)) {
                        Files.delete(existing)
                    }
                }
            }
        }
        Files.createDirectories(to)
        for ((relative, prepared) in entries) {
            val target = to.resolve(relative)
            if (Files.isDirectory(prepared, NOFOLLOW_LINKS)) {
                Files.createDirectories(target)
            } else {
                require(Files.isRegularFile(prepared, NOFOLLOW_LINKS)) { "Unsupported source entry: $prepared" }
                if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.mismatch(prepared, target) != -1L) {
                    Files.createDirectories(target.parent)
                    Files.copy(prepared, target, REPLACE_EXISTING, COPY_ATTRIBUTES)
                    Files.setLastModifiedTime(target, FileTime.from(Instant.now()))
                }
            }
        }
    }
}
