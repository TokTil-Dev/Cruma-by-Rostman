package cruma.bgfx

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Input
import org.gradle.api.provider.Property
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class PreparePatchedMetalRuntimeTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bgfxSourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val patch: RegularFileProperty

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
        execOperations.exec {
            workingDir = prepared
            commandLine("/usr/bin/patch", "--batch", "-p1", "-i", patch.get().asFile.absolutePath)
        }
        StableSourceTree.sync(prepared, target)
        fileSystemOperations.delete { delete(prepared) }
    }
}

abstract class PackagePatchedMetalRuntimeTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:OutputDirectory
    abstract val resources: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun pack() {
        val target = resources.get().asFile
        fileSystemOperations.sync {
            into(target)
            for (path in CrumaNatives.BGFX_METAL_RESOURCE_PATHS) {
                from(library) { into(path.substringBeforeLast('/')) }
            }
        }
        val checksum = library.get().asFile.sha1()
        for (path in CrumaNatives.BGFX_METAL_RESOURCE_PATHS) {
            val metadata = target.resolve("META-INF/$path.sha1")
            metadata.parentFile.mkdirs()
            metadata.writeText(checksum)
        }
    }
}

abstract class CompileMetalRayQueryBridgeTask : DefaultTask() {
    @get:Input
    abstract val buildPathPrefix: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val library: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val output = library.get().asFile
        output.parentFile.mkdirs()
        execOperations.exec {
            commandLine(
                "xcrun", "--sdk", "macosx", "clang++",
                "-std=c++20", "-fobjc-arc", "-fvisibility=hidden",
                "-dynamiclib", "-O2", "-gline-tables-only",
                "-ffile-prefix-map=${buildPathPrefix.get()}=.",
                "-fdebug-prefix-map=${buildPathPrefix.get()}=.",
                "-fdebug-compilation-dir=.",
                "-arch", "arm64", "-arch", "x86_64",
                "-mmacosx-version-min=13.0",
                "-framework", "Foundation", "-framework", "Metal",
                "-o", output.absolutePath,
                source.get().asFile.absolutePath
            )
        }
        output.resolveSibling(output.name + ".dSYM").deleteRecursively()
    }
}
