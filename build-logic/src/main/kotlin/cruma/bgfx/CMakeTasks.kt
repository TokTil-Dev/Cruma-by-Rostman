package cruma.bgfx

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class CMakeConfigureTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    abstract val definitions: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val additionalSources: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val cmakeExecutable: Property<String>

    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @get:OutputFile
    abstract val cmakeCache: RegularFileProperty

    @get:OutputFile
    abstract val resolvedCMakeFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun configure() {
        val cmake = CMakeResolver.resolve(cmakeExecutable.orNull)
        val buildDir = buildDirectory.get().asFile
        CMakeResolver.discardMismatchedCache(buildDir, cmake.generator)
        buildDir.mkdirs()
        execOperations.exec {
            commandLine(
                listOf(cmake.executable) + cmake.generatorArguments + listOf(
                    "-S", sourceDirectory.get().asFile.absolutePath,
                    "-B", buildDir.absolutePath
                ) + definitions.get()
            )
        }
        resolvedCMakeFile.get().asFile.writeText(cmake.executable)
    }
}

abstract class CMakeBuildTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cmakeCache: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val resolvedCMakeFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    abstract val target: Property<String>

    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val cmake = resolvedCMakeFile.get().asFile.readText().trim()
        execOperations.exec {
            commandLine(
                cmake, "--build", buildDirectory.get().asFile.absolutePath,
                "--target", target.get(), "--config", "Release",
                "--parallel", maxOf(1, Runtime.getRuntime().availableProcessors()).toString()
            )
        }
        val produced = output.get().asFile
        if (!produced.isFile) {
            throw IllegalStateException("CMake target ${target.get()} did not produce $produced")
        }
    }
}
