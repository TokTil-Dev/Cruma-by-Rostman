package cruma.bgfx

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class DownloadPinnedSourceTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val archive: RegularFileProperty

    @TaskAction
    fun download() {
        val output = archive.get().asFile
        output.parentFile.mkdirs()
        if (output.isFile && output.sha256() == sha256.get()) {
            return
        }
        val partial = output.resolveSibling(output.name + ".part")
        Files.deleteIfExists(partial.toPath())
        val request = HttpRequest.newBuilder(URI(url.get())).GET().build()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial.toPath())
            throw IllegalStateException("Download of ${url.get()} failed with HTTP ${response.statusCode()}")
        }
        val actual = partial.sha256()
        if (actual != sha256.get()) {
            Files.deleteIfExists(partial.toPath())
            throw IllegalStateException("SHA-256 mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
