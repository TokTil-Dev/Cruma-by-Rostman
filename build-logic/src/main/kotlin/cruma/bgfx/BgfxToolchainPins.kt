package cruma.bgfx

object BgfxToolchainPins {
    const val API = "156"
    const val BGFX = "780f7bf00a29573473b90dcfab9f92a7d8687702"
    const val BIMG = "371d90098b1fd017cd00205979d5ef74b8c3ed62"
    const val BX = "9e3fadf6f11380031486be704d2ff46ca143664f"
    const val BGFX_CMAKE = "dce2b11bca5af6dc08dc94bdacbfbd86f993fbd1"
    const val SHADERC = "1.19.156"

    const val CMAKE_FLOOR = "3.20"

    data class SourceArtifact(val name: String, val repository: String, val revision: String, val sha256: String) {
        val url: String get() = "https://codeload.github.com/$repository/tar.gz/$revision"
        val archiveName: String get() = "$name-$revision.tar.gz"
    }

    val sources = listOf(
        SourceArtifact("bgfxCmake", "bkaradzic/bgfx.cmake", BGFX_CMAKE,
            "e7ee84a18176e193db92c98a5e4c41499123caedd259621746229038dc315aa4"),
        SourceArtifact("bgfx", "bkaradzic/bgfx", BGFX,
            "a284219ead4d39fdb5af7ecc5011f5b8cc9ba349d8a1ba32fdf80d006f342ca2"),
        SourceArtifact("bimg", "bkaradzic/bimg", BIMG,
            "a1464cfbbbbbb1712df9231bb5c5442e3728f78110c7072d5145892e428fd937"),
        SourceArtifact("bx", "bkaradzic/bx", BX,
            "84740909a73336fa6192f3489cff8ba338b1c525103c291cbf7554a77002eb1a"),
    )

    fun manifestPins(lwjgl: String): Map<String, String> = linkedMapOf(
        "lwjgl" to lwjgl,
        "bgfx.api" to API,
        "bgfx.revision" to BGFX,
        "bimg.revision" to BIMG,
        "bx.revision" to BX,
        "bgfx.cmake.revision" to BGFX_CMAKE,
        "shaderc" to SHADERC,
    )
}
