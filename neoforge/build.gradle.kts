plugins {
    `java-library`
    alias(libs.plugins.neoforge.moddev)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.mixin)
}

val libBundle = configurations.create("libBundle") {
    isTransitive = false
}

dependencies {
    libBundle(libs.toml4j)
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
    archiveFileName.set("net-bridge-neoforge-${project.version}.jar")
    from(sourceSets.named("main").map { it.output })
    from(project(":common").the<SourceSetContainer>()["main"].output)
    from(cdylibDir) {
        into("native/")
    }
    from(libBundle.elements.map { elements ->
        elements.map { zipTree(it.asFile) }
    }) {
        exclude("META-INF/**")
    }
}

val neoforgeJarConfig = configurations.create("neoforgeJarConfig") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(neoforgeJarConfig.name, jarNeoForge)
}
