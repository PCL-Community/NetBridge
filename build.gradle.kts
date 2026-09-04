import top.tangge233.netbridge.build.BuildNativeLibrary

plugins {
    id("netbridge.java-conventions") apply false
    alias(libs.plugins.neoforge.moddev) apply false
    alias(libs.plugins.fabric.loom) apply false
}

allprojects {
    group = property("mavenGroup").toString()
    version = property("modVersion").toString()
}

val nativeProfileProperty = providers
        .gradleProperty("nativeProfile")
        .orElse("debug")
val skipNativeBuildProperty = providers
        .gradleProperty("skipNativeBuild")
        .map { true }
        .orElse(false)

tasks.register<BuildNativeLibrary>("buildCdylib") {
    group = "build"
    description = "Builds the native Rust cdylib and stages it for packaging."
    profile.set(nativeProfileProperty)
    skipNativeBuild.set(skipNativeBuildProperty)
    cargoDir.set(layout.projectDirectory.dir("rust"))
    outputDir.set(layout.buildDirectory.dir("native"))
}

val fabricJarConfig = configurations.create("fabricJarConfig") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val neoforgeJarConfig = configurations.create("neoforgeJarConfig") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    fabricJarConfig(
        project(
            mapOf(
                "path" to ":fabric",
                "configuration" to "fabricJarConfig"
            )
        )
    )
    neoforgeJarConfig(
        project(
            mapOf(
                "path" to ":neoforge",
                "configuration" to "neoforgeJarConfig"
            )
        )
    )
}

tasks.register<Copy>("assembleAll") {
    group = "build"
    description = "Assembles both platform mod jars into root build/libs/."
    dependsOn(tasks.named("buildCdylib"))
    from(fabricJarConfig) {
        rename { "net-bridge-fabric-${project.version}.jar" }
    }
    from(neoforgeJarConfig) {
        rename { "net-bridge-neoforge-${project.version}.jar" }
    }
    into(layout.buildDirectory.dir("libs"))
}

tasks.register("printVersions") {
    doLast {
        val mcVer = libs.versions.minecraft.get()
        val neoVer = libs.versions.neoforge.asProvider().get()
        println("Minecraft: $mcVer (NeoForge $neoVer / Fabric $mcVer)")
    }
}
