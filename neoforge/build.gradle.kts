plugins {
    id("netbridge.java-conventions")
    alias(libs.plugins.neoforge.moddev)
}

dependencies {
    implementation(project(":common"))
    implementation(libs.mixin)
}

sourceSets.named("main") {
    java.srcDir(rootProject.layout.projectDirectory.dir("minecraft/src/main/java"))
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(mapOf("version" to project.version))
    }
}

neoForge {
    version.set(libs.versions.neoforge.asProvider())
    validateAccessTransformers.set(true)
    mods {
        create("netbridge") {
            sourceSet(sourceSets.named("main").get())
        }
    }
}

val cdylibDir = rootProject.layout.buildDirectory.dir("native")

val jarNeoForge = tasks.register<Jar>("jarNeoForge") {
    dependsOn(rootProject.tasks.named("buildCdylib"))
    dependsOn(rootProject.tasks.named("generateNativeManifest"))
    archiveFileName.set("net-bridge-neoforge-${project.version}.jar")
    from(sourceSets.named("main").map { it.output })
    from(project(":common").the<SourceSetContainer>()["main"].output)
    from(cdylibDir) {
        into("native/")
    }
}

val neoforgeJarConfig = configurations.create("neoforgeJarConfig") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(neoforgeJarConfig.name, jarNeoForge)
}
