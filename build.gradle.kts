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
    cargoDir.set(layout.projectDirectory.dir("rust/net-bridge-native"))
    outputDir.set(layout.buildDirectory.dir("native"))
}

val syncedCopies = listOf(
    listOf(
        "mc/NativeClientTransport.java",
        "mc/NativeClientTransport.java"
    ),
    listOf(
        "mc/NativeServerTransport.java",
        "mc/NativeServerTransport.java"
    ),
    listOf(
        "mixin/ClientboundStatusResponsePacketMixin.java",
        "mixin/ClientboundStatusResponsePacketMixin.java"
    ),
    listOf(
        "mixin/ConnectionMixin.java",
        "mixin/ConnectionMixin.java"
    ),
    listOf(
        "mixin/ConnectScreenMixin.java",
        "mixin/ConnectScreenMixin.java"
    ),
    listOf(
        "mixin/DebugScreenOverlayMixin.java",
        "mixin/DebugScreenOverlayMixin.java"
    ),
    listOf(
        "mixin/JoinMultiplayerScreenMixin.java",
        "mixin/JoinMultiplayerScreenMixin.java"
    ),
    listOf(
        "mixin/ServerStatusPingerResponseMixin.java",
        "mixin/ServerStatusPingerResponseMixin.java"
    ),
    listOf(
        "mixin/StatusResponseWriteMixin.java",
        "mixin/StatusResponseWriteMixin.java"
    )
)

val fabricBase = layout.projectDirectory.dir(
    "fabric/src/main/java/top/tangge233/netbridge/fabric"
)
val neoforgeBase = layout.projectDirectory.dir(
    "neoforge/src/main/java/top/tangge233/netbridge/neoforge"
)

tasks.register("checkSyncedCopies") {
    group = "verification"
    description = "Verifies fabric/neoforge source copies stay in sync."
    doLast {
        fun normalize(file: File): String = file.readLines()
                .filter { line ->
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("package ") && !t.startsWith("import ")
                }.joinToString("\n") {
                    it.trim()
                }

        val failed = mutableListOf<String>()
        for (pair in syncedCopies) {
            val fabFile = fabricBase.file(pair[0]).asFile
            val neoFile = neoforgeBase.file(pair[1]).asFile
            if (!fabFile.exists() || !neoFile.exists()) {
                val side = if (fabFile.exists()) "neoforge" else "fabric"
                failed.add("${pair[0]}: missing ($side side)")
            } else if (normalize(fabFile) != normalize(neoFile)) {
                failed.add("${pair[0]}: content diverged between :fabric and :neoforge")
            }
        }
        if (failed.isNotEmpty()) {
            throw GradleException("Synced source copies out of sync:\n  ${failed.joinToString("\n  ")}")
        }
        logger.lifecycle("synced copies OK (${syncedCopies.size} pairs)")
    }
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
    description = "Assemble both platform mod jars into root build/libs/."
    dependsOn(tasks.named("buildCdylib"))
    dependsOn(tasks.named("checkSyncedCopies"))
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
