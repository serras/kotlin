/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.native

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager
import org.junit.jupiter.api.DisplayName
import java.nio.file.Paths
import kotlin.io.path.appendText
import kotlin.io.path.deleteRecursively

class NativeDownloadAndPlatformLibsNonParallelIT : KGPDaemonsBaseTest() {

    private val platformName: String = HostManager.platformName()
    private val currentCompilerVersion = NativeCompilerDownloader.DEFAULT_KONAN_VERSION


    @DisplayName("Downloading K/N distribution in default .konan dir")
    @GradleTest
    fun testLibrariesGenerationInDefaultKonanDir(gradleVersion: GradleVersion) {

        checkThatDefaultKonanHasNotBeenCreated()

        val userHomeDir = System.getProperty("user.home")
        platformLibrariesProject("linuxX64", gradleVersion = gradleVersion) {
            build("assemble", buildOptions = defaultBuildOptions.copy(konanDataDir = null)) {
                assertOutputContains("Kotlin/Native distribution: .*kotlin-native-prebuilt-$platformName".toRegex())
                assertOutputDoesNotContain("Generate platform libraries for ")

                // checking that konan was downloaded and native dependencies were not downloaded into ~/.konan dir
                assertDirectoryExists(Paths.get("$userHomeDir/.konan/dependencies"))
                assertDirectoryExists(Paths.get("$userHomeDir/.konan/kotlin-native-prebuilt-$platformName-$currentCompilerVersion"))
            }
        }

        // clean ~/.konan after test it should not be with all inheritors of KGPBaseTest
        Paths.get("$userHomeDir/.konan").deleteRecursively()

    }

    private fun platformLibrariesProject(
        vararg targets: String,
        gradleVersion: GradleVersion,
        test: TestProject.() -> Unit = {},
    ) {
        nativeProject("native-platform-libraries", gradleVersion) {
            buildGradleKts.appendText(
                targets.joinToString(prefix = "\n", separator = "\n") {
                    "kotlin.$it()"
                }
            )
            test()
        }
    }
}