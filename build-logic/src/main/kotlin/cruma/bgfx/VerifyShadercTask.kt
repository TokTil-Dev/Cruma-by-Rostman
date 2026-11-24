package cruma.bgfx

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject

abstract class VerifyShadercTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shaderc: RegularFileProperty

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val expectedRevision: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nativeJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val stdout = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(shaderc.get().asFile.absolutePath, "--version")
            standardOutput = stdout
        }
        val version = stdout.toString(Charsets.UTF_8).trim()
        if (!version.endsWith("version ${expectedVersion.get()}.")) {
            throw IllegalStateException("Unexpected shaderc version: '$version', expected ${expectedVersion.get()}")
        }
        val jars = nativeJars.files.filter { it.name.contains("-natives-") && it.name.endsWith(".jar") }
        if (jars.isEmpty()) {
            throw IllegalStateException("No LWJGL bgfx native artifacts were resolved")
        }
        for (jar in jars) {
            ZipFile(jar).use { zip ->
                val revisions = zip.entries().asSequence().filter {
                    it.name.endsWith("libbgfx.dylib.git") || it.name.endsWith("libbgfx.so.git") || it.name.endsWith("bgfx.dll.git")
                }.toList()
                if (revisions.isEmpty()) {
                    throw IllegalStateException("No embedded bgfx revision metadata in ${jar.name}")
                }
                for (entry in revisions) {
                    val revision = zip.getInputStream(entry).bufferedReader().readText().trim()
                    if (revision != expectedRevision.get()) {
                        throw IllegalStateException("${jar.name} embeds bgfx $revision, shaderc is pinned to ${expectedRevision.get()}")
                    }
                }
            }
        }
        report.get().asFile.writeText("shaderc=$version\nbgfx.revision=${expectedRevision.get()}\nnatives=${jars.joinToString(",") { it.name }}\n")
    }
}
