import cruma.bgfx.BgfxToolchainPins
import cruma.bgfx.CMakeBuildTask
import cruma.bgfx.CMakeConfigureTask
import cruma.bgfx.CompileBgfxShaderTask
import cruma.bgfx.CompileMetalRayQueryBridgeTask
import cruma.bgfx.CrumaNatives
import cruma.bgfx.DownloadPinnedSourceTask
import cruma.bgfx.ExtractToolchainTask
import cruma.bgfx.GenerateShaderManifestTask
import cruma.bgfx.Host
import cruma.bgfx.PackagePatchedMetalRuntimeTask
import cruma.bgfx.PackagePatchedRuntimeTask
import cruma.bgfx.PreparePatchedMetalRuntimeTask
import cruma.bgfx.PreparePatchedRuntimeTask
import cruma.bgfx.ShaderSourceInventory
import cruma.bgfx.ValidateShaderManifestTask
import cruma.bgfx.VerifyShadercTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")
val lwjglVersion = libs.findVersion("lwjgl").get().requiredVersion

val toolchainDir = layout.buildDirectory.dir("bgfx-toolchain")
val downloadsDir = toolchainDir.map { it.dir("downloads") }
val sourcesDir = toolchainDir.map { it.dir("sources") }
val nativeBuildDir = toolchainDir.map { it.dir("native-build") }
val generatedShadersDir = layout.buildDirectory.dir("generated/resources/bgfx")
val bgfxSourceDir = sourcesDir.map { it.dir("bgfx") }
val bimgSourceDir = sourcesDir.map { it.dir("bimg") }
val bxSourceDir = sourcesDir.map { it.dir("bx") }
val lwjglBgfxArtifacts = configurations.named("runtimeClasspath").map { classpath ->
    classpath.incoming.artifactView {
        componentFilter { id -> id is ModuleComponentIdentifier && id.group == "org.lwjgl" && id.module == "lwjgl-bgfx" }
    }.files
}
val shadercFile = nativeBuildDir.map { it.file(Host.shadercRelativePath) }
val explicitCMake = providers.gradleProperty("bgfx.cmake.executable")
    .orElse(providers.environmentVariable("BGFX_CMAKE"))
val explicitPatch = providers.gradleProperty("bgfx.patch.executable")
    .orElse(providers.environmentVariable("BGFX_PATCH"))
val copyPoolPatches = listOf(CrumaNatives.COPY_POOL_PATCH, CrumaNatives.VULKAN_MEMORY_PATCH)
    .map { layout.projectDirectory.file("src/main/bgfx/patches/$it") }
val windowsPatches = copyPoolPatches +
    layout.projectDirectory.file("src/main/bgfx/patches/${CrumaNatives.D3D12_STAGING_PATCH}")

val downloadTasks = BgfxToolchainPins.sources.map { artifact ->
    tasks.register<DownloadPinnedSourceTask>("downloadBgfx${artifact.name.replaceFirstChar { it.uppercase() }}Source") {
        group = "bgfx toolchain"
        description = "Downloads the pinned ${artifact.name} source archive and verifies its SHA-256."
        url = artifact.url
        sha256 = artifact.sha256
        archive = downloadsDir.map { it.file(artifact.archiveName) }
    }
}

val extractBgfxToolchain = tasks.register("extractBgfxToolchain", ExtractToolchainTask::class) {
    group = "bgfx toolchain"
    description = "Extracts the checksum-verified bgfx/shaderc source tree."
    archives.from(downloadTasks)
    outputDirectory = sourcesDir
}

val configureBgfxShaderc = tasks.register("configureBgfxShaderc", CMakeConfigureTask::class) {
    group = "bgfx toolchain"
    description = "Configures the pinned host shaderc build."
    sourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory }
    buildDirectory = nativeBuildDir
    cmakeExecutable = explicitCMake
    cmakeCache = nativeBuildDir.map { it.file("CMakeCache.txt") }
    resolvedCMakeFile = nativeBuildDir.map { it.file("cmake-executable.txt") }
    definitions = listOf(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBGFX_DIR=${bgfxSourceDir.get().asFile.absolutePath}",
        "-DBIMG_DIR=${bimgSourceDir.get().asFile.absolutePath}",
        "-DBX_DIR=${bxSourceDir.get().asFile.absolutePath}",
        "-DBGFX_BUILD_EXAMPLES=OFF",
        "-DBGFX_BUILD_TESTS=OFF",
        "-DBGFX_BUILD_TOOLS=ON",
        "-DBGFX_BUILD_TOOLS_BIN2C=OFF",
        "-DBGFX_BUILD_TOOLS_SHADER=ON",
        "-DBGFX_BUILD_TOOLS_GEOMETRY=OFF",
        "-DBGFX_BUILD_TOOLS_TEXTURE=OFF",
        "-DBGFX_INSTALL=OFF",
        "-DBGFX_CUSTOM_TARGETS=OFF",
    )
}

val buildBgfxShaderc = tasks.register("buildBgfxShaderc", CMakeBuildTask::class) {
    group = "bgfx toolchain"
    description = "Builds shaderc ${BgfxToolchainPins.SHADERC} from the source revision embedded by LWJGL $lwjglVersion."
    cmakeCache = configureBgfxShaderc.flatMap { it.cmakeCache }
    resolvedCMakeFile = configureBgfxShaderc.flatMap { it.resolvedCMakeFile }
    sourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory }
    buildDirectory = nativeBuildDir
    target = "shaderc"
    output = shadercFile
}

val configuredShaderc = providers.gradleProperty("bgfx.shaderc.executable")
    .orElse(providers.environmentVariable("BGFX_SHADERC"))
    .orNull
val selectedShaderc = objects.fileProperty()
if (configuredShaderc != null) {
    selectedShaderc.set(layout.projectDirectory.file(configuredShaderc))
} else {
    selectedShaderc.set(buildBgfxShaderc.flatMap { it.output })
}
val shadercExportSource = configuredShaderc?.let { file(it).parentFile }
    ?: shadercFile.get().asFile.parentFile

val verifyBgfxShaderc = tasks.register("verifyBgfxShaderc", VerifyShadercTask::class) {
    group = "verification"
    description = "Verifies the shader compiler and LWJGL natives use the pinned bgfx API revision."
    dependsOn(extractBgfxToolchain)
    shaderc = selectedShaderc
    expectedVersion = BgfxToolchainPins.SHADERC
    expectedRevision = BgfxToolchainPins.BGFX
    nativeJars.from(lwjglBgfxArtifacts)
    report = toolchainDir.map { it.file("shaderc-verified.properties") }
}

tasks.register<Sync>("exportBgfxShaderc") {
    group = "bgfx toolchain"
    description = "Exports the verified shader compiler and its runtime libraries for the CI cache."
    dependsOn(verifyBgfxShaderc)
    into(layout.buildDirectory.dir("bgfx-shaderc-cache"))
    from(shadercExportSource) {
        include("shaderc", "shaderc.exe", "*.dll", "*.so", "*.dylib")
    }
}

val shaderSourceDir = layout.projectDirectory.dir("src/main/bgfx/shaders")
val varyingDefinitionFile = shaderSourceDir.file("varying.def.sc")
val shaderSources = providers.of(ShaderSourceInventory::class) {
    parameters.directory.set(shaderSourceDir)
}.get().map { shaderSourceDir.file(it).asFile }
val sharedShaderInputs = shaderSourceDir.asFileTree.matching { include("**/*.sc", "**/*.sh") }
val bgfxShaderHeaders = bgfxSourceDir.map { it.dir("src").asFileTree.matching { include("*.sh") } }
val shaderVariants = listOf("debug", "release")
val targetNames = Host.shaderTargets.map { it.name }
val shaderSourceNames = shaderSources.map { it.name.removeSuffix(".sc") }

val compileTasks = shaderVariants.flatMap { variant ->
    Host.shaderTargets.flatMap { target ->
        shaderSources.map { source ->
            val sourceName = source.name.removeSuffix(".sc")
            val taskSuffix = sourceName.split('_').joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
            tasks.register<CompileBgfxShaderTask>("compileBgfx${target.name.replaceFirstChar { it.uppercase() }}${variant.replaceFirstChar { it.uppercase() }}$taskSuffix") {
                group = "bgfx shaders"
                description = "Compiles ${source.name} for ${target.name} ($variant)."
                dependsOn(verifyBgfxShaderc)
                shaderc = selectedShaderc
                this.source = source
                includes.from(sharedShaderInputs, bgfxShaderHeaders)
                includeDirectories = listOf(shaderSourceDir.asFile.absolutePath, bgfxSourceDir.get().dir("src").asFile.absolutePath)
                varyingDefinition = varyingDefinitionFile
                stage = when {
                    source.name.startsWith("vs_") -> "vertex"
                    source.name.startsWith("fs_") -> "fragment"
                    else -> "compute"
                }
                platform = target.platform
                profile = target.profile
                this.variant = variant
                output = generatedShadersDir.map { it.file("bgfx/shaders/$variant/${target.name}/$sourceName.bin") }
            }
        }
    }
}

val compileBgfxShaders = tasks.register("compileBgfxShaders") {
    group = "bgfx shaders"
    description = "Compiles every bgfx shader for the active host renderer set."
    dependsOn(compileTasks)
}

val generateBgfxShaderManifest = tasks.register("generateBgfxShaderManifest", GenerateShaderManifestTask::class) {
    group = "bgfx shaders"
    description = "Writes a deterministic manifest of the pinned toolchain and every packaged shader binary."
    binaries.from(compileTasks)
    generatedDirectory = generatedShadersDir
    pins = BgfxToolchainPins.manifestPins(lwjglVersion)
    variants = shaderVariants
    targets = targetNames
    sourceNames = shaderSourceNames
    manifest = generatedShadersDir.map { it.file("bgfx/shaders/manifest.properties") }
}

val validateBgfxShaderManifest = tasks.register("validateBgfxShaderManifest", ValidateShaderManifestTask::class) {
    group = "verification"
    description = "Fails if any source/backend/variant shader binary is missing or empty."
    shaderSources.from(sharedShaderInputs)
    binaries.from(compileTasks)
    manifest = generateBgfxShaderManifest.flatMap { it.manifest }
    generatedDirectory = generatedShadersDir
    variants = shaderVariants
    targets = targetNames
    sourceNames = shaderSourceNames
    report = toolchainDir.map { it.file("shader-manifest-validated.properties") }
}

val windowsRuntimeDir = layout.buildDirectory.dir("bgfx-windows-copy-pool-runtime")
val patchedWindowsBgfxDir = windowsRuntimeDir.map { it.dir("patched-bgfx") }
val windowsRuntimeBuildDir = windowsRuntimeDir.map { it.dir("native-build") }
val windowsRuntimeResources = layout.buildDirectory.dir("generated/resources/bgfx-windows-runtime")

val preparePatchedBgfxWindowsRuntime = tasks.register("preparePatchedBgfxWindowsRuntime", PreparePatchedRuntimeTask::class) {
    group = "bgfx toolchain"
    description = "Applies the upload-copy pool, the Vulkan video-memory fallback and the Direct3D 12 staging pool to pinned bgfx with Windows DLL exports."
    enabled = Host.isWindows
    bgfxSourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory.dir("bgfx") }
    patches.from(windowsPatches)
    patchExecutable = explicitPatch
    patchedDirectory = patchedWindowsBgfxDir
}

val configurePatchedBgfxWindowsRuntime = tasks.register("configurePatchedBgfxWindowsRuntime", CMakeConfigureTask::class) {
    group = "bgfx toolchain"
    description = "Configures the pinned Windows x64 bgfx runtime with the upload-copy pool."
    enabled = Host.isWindows
    sourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory }
    additionalSources.from(preparePatchedBgfxWindowsRuntime.flatMap { it.patchedDirectory })
    buildDirectory = windowsRuntimeBuildDir
    cmakeExecutable = explicitCMake
    cmakeCache = windowsRuntimeBuildDir.map { it.file("CMakeCache.txt") }
    resolvedCMakeFile = windowsRuntimeBuildDir.map { it.file("cmake-executable.txt") }
    definitions = listOf(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_CXX_FLAGS_RELEASE=/O2 /Ob2 /DNDEBUG /experimental:deterministic /pathmap:\"${rootDir.absolutePath}=.\"",
        "-DBGFX_DIR=${patchedWindowsBgfxDir.get().asFile.absolutePath}",
        "-DBIMG_DIR=${bimgSourceDir.get().asFile.absolutePath}",
        "-DBX_DIR=${bxSourceDir.get().asFile.absolutePath}",
        "-DBGFX_LIBRARY_TYPE=SHARED",
        "-DBGFX_BUILD_EXAMPLES=OFF",
        "-DBGFX_BUILD_TESTS=OFF",
        "-DBGFX_BUILD_TOOLS=OFF",
        "-DBGFX_INSTALL=OFF",
        "-DBGFX_CUSTOM_TARGETS=OFF",
    )
}

val buildPatchedBgfxWindowsRuntime = tasks.register("buildPatchedBgfxWindowsRuntime", CMakeBuildTask::class) {
    group = "bgfx toolchain"
    description = "Builds the pinned Windows bgfx runtime with delayed-release upload-copy reuse."
    enabled = Host.isWindows
    cmakeCache = configurePatchedBgfxWindowsRuntime.flatMap { it.cmakeCache }
    resolvedCMakeFile = configurePatchedBgfxWindowsRuntime.flatMap { it.resolvedCMakeFile }
    sourceDirectory = preparePatchedBgfxWindowsRuntime.flatMap { it.patchedDirectory }
    buildDirectory = windowsRuntimeBuildDir
    target = "bgfx"
    output = windowsRuntimeBuildDir.map { it.file("cmake/bgfx/Release/bgfx.dll") }
}

val packagePatchedBgfxWindowsRuntime = tasks.register("packagePatchedBgfxWindowsRuntime", PackagePatchedRuntimeTask::class) {
    group = "native build"
    description = "Packages the patched Windows DLL and its matching LWJGL checksum."
    enabled = Host.isWindows
    library = buildPatchedBgfxWindowsRuntime.flatMap { it.output }
    resourcePath = CrumaNatives.BGFX_WINDOWS_RESOURCE
    resources = windowsRuntimeResources
}

val linuxRuntimeDir = layout.buildDirectory.dir("bgfx-linux-runtime")
val patchedLinuxBgfxDir = linuxRuntimeDir.map { it.dir("patched-bgfx") }
val linuxRuntimeBuildDir = linuxRuntimeDir.map { it.dir("native-build") }
val linuxRuntimeResources = layout.buildDirectory.dir("generated/resources/bgfx-linux-runtime")
val linuxReleaseFlags = "-O3 -DNDEBUG -ffile-prefix-map=\"${rootDir.absolutePath}=.\""

val preparePatchedBgfxLinuxRuntime = tasks.register("preparePatchedBgfxLinuxRuntime", PreparePatchedRuntimeTask::class) {
    group = "bgfx toolchain"
    description = "Applies the upload-copy pool and the Vulkan video-memory fallback to pinned bgfx for Linux."
    enabled = Host.isLinux
    bgfxSourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory.dir("bgfx") }
    patches.from(copyPoolPatches)
    patchExecutable = explicitPatch
    patchedDirectory = patchedLinuxBgfxDir
}

val configurePatchedBgfxLinuxRuntime = tasks.register("configurePatchedBgfxLinuxRuntime", CMakeConfigureTask::class) {
    group = "bgfx toolchain"
    description = "Configures the pinned Linux x64 bgfx runtime with the upload-copy pool."
    enabled = Host.isLinux
    sourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory }
    additionalSources.from(preparePatchedBgfxLinuxRuntime.flatMap { it.patchedDirectory })
    buildDirectory = linuxRuntimeBuildDir
    cmakeExecutable = explicitCMake
    cmakeCache = linuxRuntimeBuildDir.map { it.file("CMakeCache.txt") }
    resolvedCMakeFile = linuxRuntimeBuildDir.map { it.file("cmake-executable.txt") }
    definitions = listOf(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_C_FLAGS_RELEASE=$linuxReleaseFlags",
        "-DCMAKE_CXX_FLAGS_RELEASE=$linuxReleaseFlags",
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--as-needed",
        "-DBGFX_WITH_WAYLAND=OFF",
        "-DBGFX_DIR=${patchedLinuxBgfxDir.get().asFile.absolutePath}",
        "-DBIMG_DIR=${bimgSourceDir.get().asFile.absolutePath}",
        "-DBX_DIR=${bxSourceDir.get().asFile.absolutePath}",
        "-DBGFX_LIBRARY_TYPE=SHARED",
        "-DBGFX_BUILD_EXAMPLES=OFF",
        "-DBGFX_BUILD_TESTS=OFF",
        "-DBGFX_BUILD_TOOLS=OFF",
        "-DBGFX_INSTALL=OFF",
        "-DBGFX_CUSTOM_TARGETS=OFF",
    )
}

val buildPatchedBgfxLinuxRuntime = tasks.register("buildPatchedBgfxLinuxRuntime", CMakeBuildTask::class) {
    group = "bgfx toolchain"
    description = "Builds the pinned Linux bgfx runtime with the upload-copy pool and the Vulkan video-memory fallback."
    enabled = Host.isLinux
    cmakeCache = configurePatchedBgfxLinuxRuntime.flatMap { it.cmakeCache }
    resolvedCMakeFile = configurePatchedBgfxLinuxRuntime.flatMap { it.resolvedCMakeFile }
    sourceDirectory = preparePatchedBgfxLinuxRuntime.flatMap { it.patchedDirectory }
    buildDirectory = linuxRuntimeBuildDir
    target = "bgfx"
    output = linuxRuntimeBuildDir.map { it.file("cmake/bgfx/libbgfx.so") }
}

val packagePatchedBgfxLinuxRuntime = tasks.register("packagePatchedBgfxLinuxRuntime", PackagePatchedRuntimeTask::class) {
    group = "native build"
    description = "Packages the patched Linux library and its matching LWJGL checksum."
    enabled = Host.isLinux
    library = buildPatchedBgfxLinuxRuntime.flatMap { it.output }
    resourcePath = CrumaNatives.BGFX_LINUX_RESOURCE
    resources = linuxRuntimeResources
}

val metalRuntimeDir = layout.buildDirectory.dir("bgfx-metal-runtime")
val patchedBgfxDir = metalRuntimeDir.map { it.dir("patched-bgfx") }
val metalRuntimeBuildDir = metalRuntimeDir.map { it.dir("native-build") }
val metalRuntimeResources = layout.buildDirectory.dir("generated/resources/bgfx-metal-runtime")

val preparePatchedBgfxMetalRuntime = tasks.register("preparePatchedBgfxMetalRuntime", PreparePatchedMetalRuntimeTask::class) {
    group = "bgfx toolchain"
    description = "Copies pinned bgfx and applies the Cruma Metal shared-event extension."
    enabled = Host.isMacOs
    bgfxSourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory.dir("bgfx") }
    patch = layout.projectDirectory.file("src/main/bgfx/patches/${CrumaNatives.METAL_PATCH}")
    patchedDirectory = patchedBgfxDir
}

val configurePatchedBgfxMetalRuntime = tasks.register("configurePatchedBgfxMetalRuntime", CMakeConfigureTask::class) {
    group = "bgfx toolchain"
    description = "Configures the pinned universal bgfx Metal runtime."
    enabled = Host.isMacOs
    sourceDirectory = extractBgfxToolchain.flatMap { it.outputDirectory }
    additionalSources.from(preparePatchedBgfxMetalRuntime.flatMap { it.patchedDirectory })
    buildDirectory = metalRuntimeBuildDir
    cmakeExecutable = explicitCMake
    cmakeCache = metalRuntimeBuildDir.map { it.file("CMakeCache.txt") }
    resolvedCMakeFile = metalRuntimeBuildDir.map { it.file("cmake-executable.txt") }
    definitions = listOf(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64",
        "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -ffile-prefix-map=\"${rootDir.absolutePath}=.\" -fdebug-prefix-map=\"${rootDir.absolutePath}=.\" -fdebug-compilation-dir=.",
        "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -ffile-prefix-map=\"${rootDir.absolutePath}=.\" -fdebug-prefix-map=\"${rootDir.absolutePath}=.\" -fdebug-compilation-dir=.",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0",
        "-DBGFX_DIR=${patchedBgfxDir.get().asFile.absolutePath}",
        "-DBIMG_DIR=${bimgSourceDir.get().asFile.absolutePath}",
        "-DBX_DIR=${bxSourceDir.get().asFile.absolutePath}",
        "-DBGFX_LIBRARY_TYPE=SHARED",
        "-DBGFX_BUILD_EXAMPLES=OFF",
        "-DBGFX_BUILD_TESTS=OFF",
        "-DBGFX_BUILD_TOOLS=OFF",
        "-DBGFX_INSTALL=OFF",
        "-DBGFX_CUSTOM_TARGETS=OFF",
    )
}

val buildPatchedBgfxMetalRuntime = tasks.register("buildPatchedBgfxMetalRuntime", CMakeBuildTask::class) {
    group = "bgfx toolchain"
    description = "Builds the pinned universal bgfx runtime with GPU event synchronization."
    enabled = Host.isMacOs
    cmakeCache = configurePatchedBgfxMetalRuntime.flatMap { it.cmakeCache }
    resolvedCMakeFile = configurePatchedBgfxMetalRuntime.flatMap { it.resolvedCMakeFile }
    sourceDirectory = preparePatchedBgfxMetalRuntime.flatMap { it.patchedDirectory }
    buildDirectory = metalRuntimeBuildDir
    target = "bgfx"
    output = metalRuntimeBuildDir.map { it.file("cmake/bgfx/libbgfx.dylib") }
}

val packagePatchedBgfxMetalRuntime = tasks.register("packagePatchedBgfxMetalRuntime", PackagePatchedMetalRuntimeTask::class) {
    group = "native build"
    description = "Packages the universal patched bgfx runtime and matching LWJGL checksums."
    enabled = Host.isMacOs
    library = buildPatchedBgfxMetalRuntime.flatMap { it.output }
    resources = metalRuntimeResources
}

val metalRayQueryResources = layout.buildDirectory.dir("generated/resources/metal-ray-query")

val compileMetalRayQueryBridge = tasks.register("compileMetalRayQueryBridge", CompileMetalRayQueryBridgeTask::class) {
    buildPathPrefix = rootDir.absolutePath
    group = "native build"
    description = "Builds the optional universal Metal ray-query bridge on macOS."
    enabled = Host.isMacOs
    source = layout.projectDirectory.file("src/main/native/metal_ray_queries.mm")
    library = metalRayQueryResources.map { it.file(CrumaNatives.RAY_QUERY_RESOURCE) }
}

sourceSets.named("main") {
    resources.srcDir(generatedShadersDir)
    resources.srcDir(metalRuntimeResources)
    resources.srcDir(windowsRuntimeResources)
    resources.srcDir(linuxRuntimeResources)
    resources.srcDir(metalRayQueryResources)
}

if (Host.isMacOs) {
    sourceSets.configureEach {
        runtimeClasspath += files(packagePatchedBgfxMetalRuntime)
    }
}

if (Host.isWindows) {
    sourceSets.configureEach {
        runtimeClasspath += files(packagePatchedBgfxWindowsRuntime)
    }
}

if (Host.isLinux) {
    sourceSets.configureEach {
        runtimeClasspath += files(packagePatchedBgfxLinuxRuntime)
    }
}

tasks.named("processResources") {
    dependsOn(validateBgfxShaderManifest, packagePatchedBgfxMetalRuntime, packagePatchedBgfxWindowsRuntime,
        packagePatchedBgfxLinuxRuntime, compileMetalRayQueryBridge)
}
