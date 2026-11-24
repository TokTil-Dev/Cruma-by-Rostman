import cruma.bgfx.Host

plugins {
    id("cruma.java-conventions")
    id("cruma.bgfx-toolchain")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.typesafe.config)
    implementation(libs.imgui.java.app)
    implementation(libs.bundles.lwjgl)
    implementation(libs.bundles.eclipse.collections)
    implementation(libs.fastjson2)
    implementation(libs.log4j.core)
    for (classifier in listOf("natives-macos", "natives-macos-arm64", "natives-windows", "natives-linux")) {
        for (module in listOf(libs.lwjgl.core, libs.lwjgl.glfw, libs.lwjgl.stb, libs.lwjgl.bgfx, libs.lwjgl.jemalloc, libs.lwjgl.tinyfd)) {
            runtimeOnly(variantOf(module) { classifier(classifier) })
        }
    }
}

val viewerJvmArgs = listOf(
    "-Xmx12g", "-XX:+UseZGC", "-XX:ZUncommitDelay=10",
    "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
) + if (Host.isMacOs) listOf("-XstartOnFirstThread", "-Djava.awt.headless=true") else emptyList()

application {
    applicationName = "cruma"
    mainClass = "com.rost.cruma.viewer.Cruma"
    applicationDefaultJvmArgs = viewerJvmArgs
}

tasks.named<JavaExec>("run") {
    jvmArgs(viewerJvmArgs)
    if (Host.isMacOs) {
        jvmArgs("-Xdock:name=Cruma", "-Xdock:icon=${layout.projectDirectory.file("src/main/resources/ui/branding/cruma.icns").asFile}")
    }
}

val bgfxChecksums = tasks.register<Jar>("bgfxChecksums") {
    archiveBaseName = "cruma-bgfx-checksums"
    from(tasks.named(when {
        Host.isMacOs -> "packagePatchedBgfxMetalRuntime"
        Host.isWindows -> "packagePatchedBgfxWindowsRuntime"
        else -> "packagePatchedBgfxLinuxRuntime"
    })) {
        include("META-INF/**")
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    classpath = files(classpath, bgfxChecksums)
}

distributions.main {
    contents {
        from(layout.settingsDirectory.file("LICENSE"))
        into("lib") { from(bgfxChecksums) }
    }
}
