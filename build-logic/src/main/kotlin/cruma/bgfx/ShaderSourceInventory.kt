package cruma.bgfx

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

abstract class ShaderSourceInventory : ValueSource<List<String>, ShaderSourceInventory.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
    }

    override fun obtain(): List<String> = parameters.directory.get().asFile.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.matches(Regex("(?:vs|fs|cs)_.*\\.sc")) }
        .map { it.name }
        .sorted()
}
