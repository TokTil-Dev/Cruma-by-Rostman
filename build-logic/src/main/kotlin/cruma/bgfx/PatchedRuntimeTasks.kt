package cruma.bgfx

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class PreparePatchedRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bgfxSourceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val patches: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val patchExecutable: Property<String>

    @get:OutputDirectory
    abstract val patchedDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun prepare() {
        val target = patchedDirectory.get().asFile
        val prepared = temporaryDir.resolve("sources")
        fileSystemOperations.sync {
            from(bgfxSourceDirectory)
            into(prepared)
        }
        val candidates = listOfNotNull(
            patchExecutable.orNull,
            System.getenv("ProgramFiles")?.let { File(it, "Git/usr/bin/patch.exe").absolutePath },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Programs/Git/usr/bin/patch.exe").absolutePath },
        )
        val executable = patchExecutable.orNull?.takeIf { it.isNotBlank() }
            ?: candidates.firstOrNull { File(it).isFile } ?: "patch"
        for (patch in patches) {
            execOperations.exec {
                workingDir = prepared
                commandLine(executable, "--batch", "--fuzz=0", "-p1", "-i", patch.absolutePath)
            }
        }
        StableSourceTree.sync(prepared, target)
        fileSystemOperations.delete { delete(prepared) }
    }
}

abstract class PackagePatchedRuntimeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:Input
    abstract val resourcePath: Property<String>

    @get:OutputDirectory
    abstract val resources: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun pack() {
        val target = resources.get().asFile
        val path = resourcePath.get()
        fileSystemOperations.sync {
            into(target)
            from(library) { into(path.substringBeforeLast('/')) }
        }
        val metadata = target.resolve("META-INF/$path.sha1")
        metadata.parentFile.mkdirs()
        metadata.writeText(library.get().asFile.sha1())
    }
}
