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
import javax.inject.Inject

abstract class CompileBgfxShaderTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shaderc: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val source: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includes: ConfigurableFileCollection

    @get:Input
    abstract val includeDirectories: org.gradle.api.provider.ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val varyingDefinition: RegularFileProperty

    @get:Input
    abstract val stage: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val variant: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        val arguments = mutableListOf(
            shaderc.get().asFile.absolutePath,
            "-f", source.get().asFile.absolutePath,
            "-o", out.absolutePath,
            "--type", stage.get(),
            "--platform", platform.get(),
            "-p", profile.get(),
        )
        for (directory in includeDirectories.get()) {
            arguments += listOf("-i", directory)
        }
        arguments += "--Werror"
        val compute = stage.get() == "compute"
        if (!compute) {
            arguments += listOf("--varyingdef", varyingDefinition.get().asFile.absolutePath)
        }
        if (variant.get() == "debug") {
            if (!compute) {
                arguments += "--debug"
            }
            arguments += listOf("-O", "0")
        } else {
            arguments += listOf("-O", if (compute) "1" else "3")
        }
        execOperations.exec { commandLine(arguments) }
    }
}
