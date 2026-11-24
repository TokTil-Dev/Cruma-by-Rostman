plugins {
    id("cruma.java-conventions")
    `java-library`
}

dependencies {
    api(libs.glm)
    api(libs.kool)
    api(libs.bundles.eclipse.collections)
    implementation(libs.jorbis)
}
