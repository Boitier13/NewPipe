/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.jetbrains.kotlin.multiplatform)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.compose.multiplatform)
    alias(libs.plugins.koin)
    alias(libs.plugins.jetbrains.kotlinx.serialization)
}

val buildConfigGenerator = tasks.register<Sync>("buildConfigGenerator") {
    val buildConfigPackage = NEWPIPE_APPLICATION_ID_NEW
    val rawClass = """
        package $buildConfigPackage

        object BuildConfig {
            const val VERSION_NAME = "$NEWPIPE_VERSION_NAME"
            const val APP_NAME = "NewPipe"
        }
    """.trimIndent()
    from(resources.text.fromString(rawClass)) {
        rename { "BuildConfig.kt" }
        into(buildConfigPackage.replace(".", "/"))
    }
    into(layout.buildDirectory.dir("generated/kotlin/"))
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes"
        )
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi"
        )
    }

    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir(buildConfigGenerator.map { it.destinationDir })
            dependencies {
                implementation(libs.jetbrains.compose.runtime)
                implementation(libs.jetbrains.compose.foundation)
                implementation(libs.jetbrains.compose.material3)
                implementation(libs.jetbrains.compose.ui)
                implementation(libs.jetbrains.compose.resources)
                implementation(libs.jetbrains.compose.preview)

                implementation(libs.jetbrains.lifecycle.viewmodel)

                // Use API as java compiler cannot see NavKey for some reason
                api(libs.jetbrains.navigation3.ui)
                implementation(libs.jetbrains.lifecycle.navigation3)
                implementation(libs.kotlinx.serialization.json)

                implementation(libs.koin.compose.navigation3)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.koin.annotations)

                implementation(libs.coil.compose)
                implementation(libs.russhwolf.settings.core)
                implementation(libs.touchlab.kermit)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test.core)
            implementation(libs.jetbrains.compose.test.ui)
            implementation(libs.russhwolf.settings.test)
        }
        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.coil.network.okhttp)
            implementation(libs.jsoup)
            implementation(libs.newpipe.extractor)
            implementation(libs.squareup.okhttp)
            implementation(libs.squareup.okhttp.brotli)
            implementation(libs.vlcj)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
