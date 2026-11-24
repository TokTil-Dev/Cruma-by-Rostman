package cruma.bgfx

data class ShaderTarget(val name: String, val platform: String, val profile: String)

object Host {
    private val osName = System.getProperty("os.name").lowercase()
    val isMacOs = osName.contains("mac")
    val isWindows = osName.contains("win")
    val isLinux = !isMacOs && !isWindows

    val shaderTargets: List<ShaderTarget> = buildList {
        if (isMacOs) add(ShaderTarget("metal", "osx", "metal"))
        add(ShaderTarget("spirv", "linux", "spirv"))
        if (isWindows) add(ShaderTarget("dx11", "windows", "s_5_0"))
    }

    val shadercRelativePath: String = if (isWindows) "cmake/bgfx/Release/shaderc.exe" else "cmake/bgfx/shaderc"
}
