package top.tangge233.netbridge.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

@DisableCachingByDefault(
    because = "Cargo manages its own incremental compilation cache"
)
abstract class BuildNativeLibrary @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val skipNativeBuild: Property<Boolean>

    @get:InputDirectory
    abstract val cargoDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        profile.convention("debug")
        skipNativeBuild.convention(false)
        onlyIf { !skipNativeBuild.get() }
    }

    @TaskAction
    fun build() {
        val prof = profile.get()
        if (prof !in listOf("debug", "release")) {
            throw GradleException("nativeProfile must be debug or release, got: $prof")
        }

        val cargoDirectory = cargoDir.get().asFile
        val cargoArgs = mutableListOf("cargo", "build", "-p", "net-bridge-native")
        if (prof == "release") {
            cargoArgs.add("--release")
        }

        val execResult = execOperations.exec {
            workingDir(cargoDirectory)
            commandLine(cargoArgs)
        }
        if (execResult.exitValue != 0) {
            throw GradleException("cargo build failed with exit code ${execResult.exitValue}")
        }

        val srcCdylib = cargoDirectory.resolve("target/$prof/${NativePlatform.cdylibName}")
        if (!srcCdylib.exists()) {
            throw GradleException("Rust cdylib not found after cargo build: $srcCdylib")
        }

        val targetDir = outputDir.get().asFile.resolve(NativePlatform.subdir)
        targetDir.mkdirs()
        val dst = targetDir.resolve(NativePlatform.cdylibName)
        Files.copy(
            srcCdylib.toPath(),
            dst.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        logger.lifecycle("copied native ($prof): $srcCdylib -> $dst")
    }

}
