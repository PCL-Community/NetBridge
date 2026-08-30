import top.tangge233.netbridge.build.NativePlatform

plugins {
    id("netbridge.java-conventions")
    id("netbridge.test-conventions")
}

dependencies {
    implementation(libs.slf4j)
    implementation(libs.gson)
    api(libs.toml4j)
    api(libs.nightconfig.toml)
    implementation(libs.bundles.netty)
}

val cdylibDir = rootProject.layout.buildDirectory.dir("native")

val nativeIntegrationTest = tasks.register<Test>("nativeIntegrationTest") {
    description = "Runs native integration tests requiring the compiled Rust cdylib."
    group = "verification"

    useJUnitPlatform()
    dependsOn(rootProject.tasks.named("buildCdylib"))
    jvmArgs(
        "-Djava.library.path=${cdylibDir.get().asFile}/${NativePlatform.subdir}"
    )
    if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_24)) {
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow"
        )
    }
}

tasks.named<Jar>("jar") {
    dependsOn(rootProject.tasks.named("buildCdylib"))
    from(cdylibDir) {
        into("native/")
    }
}
