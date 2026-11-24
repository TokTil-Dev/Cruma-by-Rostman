package cruma.bgfx

import java.io.File
import java.io.IOException

data class ResolvedCMake(val executable: String, val generator: String?, val generatorArguments: List<String>)

object CMakeResolver {
    private val visualStudioToolchains = mapOf(
        "2022" to Pair("Visual Studio 17 2022", "3.21"),
        "2019" to Pair("Visual Studio 16 2019", "3.20"),
    )

    fun resolve(explicitExecutable: String?): ResolvedCMake {
        var required = BgfxToolchainPins.CMAKE_FLOOR
        var generator: String? = null
        var generatorArguments = emptyList<String>()
        if (Host.isWindows) {
            val productLine = queryVisualStudio("catalog_productLineVersion")
            val toolchain = productLine?.let { visualStudioToolchains[it] }
                ?: throw IllegalStateException(
                    "No supported Visual Studio with the C++ toolset was found (detected: ${productLine ?: "none"}). " +
                        "Install the 'Desktop development with C++' workload for Visual Studio " +
                        visualStudioToolchains.keys.joinToString(" or ") + "."
                )
            required = toolchain.second
            generator = toolchain.first
            generatorArguments = listOf("-G", generator, "-A", "x64")
        }
        val candidates = mutableListOf<String>()
        explicitExecutable?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        candidates.add("cmake")
        if (Host.isWindows) {
            queryVisualStudio("installationPath")?.let {
                candidates.add(File(it, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe").absolutePath)
            }
        }
        val rejected = mutableListOf<String>()
        for (candidate in candidates) {
            val version = runCommand(listOf(candidate, "--version"))?.let(::parseVersion) ?: continue
            if (isAtLeast(version, required)) {
                return ResolvedCMake(candidate, generator, generatorArguments)
            }
            rejected.add("$candidate (${version.joinToString(".")})")
        }
        throw IllegalStateException(
            "CMake $required or newer is required to build shaderc" +
                (generator?.let { " with the '$it' generator" } ?: "") + ". " +
                (if (rejected.isEmpty()) "No cmake executable was found." else "Found ${rejected.joinToString(", ")}.") +
                " Install a newer CMake, or point -Pbgfx.cmake.executable=<path> at one."
        )
    }

    fun discardMismatchedCache(buildDir: File, generator: String?) {
        val cache = File(buildDir, "CMakeCache.txt")
        if (generator == null || !cache.isFile) return
        val key = "CMAKE_GENERATOR:INTERNAL="
        val cached = cache.readLines().firstOrNull { it.startsWith(key) }?.substring(key.length)
        if (cached != generator) {
            buildDir.deleteRecursively()
        }
    }

    private fun queryVisualStudio(property: String): String? {
        val programFiles = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val vswhere = File(programFiles, "Microsoft Visual Studio/Installer/vswhere.exe")
        if (!vswhere.isFile) return null
        return runCommand(
            listOf(
                vswhere.absolutePath, "-latest", "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", property
            )
        )?.lines()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun runCommand(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) output.trim() else null
    } catch (ignored: IOException) {
        null
    }

    private fun parseVersion(text: String): List<Int>? {
        val match = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(text) ?: return null
        return listOf(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].ifEmpty { "0" }.toInt())
    }

    private fun isAtLeast(actual: List<Int>, required: String): Boolean {
        val expected = parseVersion(required)!!
        for (i in 0 until 3) {
            if (actual[i] != expected[i]) return actual[i] > expected[i]
        }
        return true
    }
}
