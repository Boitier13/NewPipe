/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.jetbrains.compose.multiplatform)
    alias(libs.plugins.about.libraries)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.jetbrains.coroutines.swing)
    implementation(libs.jetbrains.compose.preview)

    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "net.newpipe.app.MainKt"

        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dsun.awt.noerasebackground=true"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "BoitierTube"
            packageVersion = NEWPIPE_VERSION_NAME
            description = "Lecteur video libre pour ordinateur"
            vendor = "BoitierTube"
            licenseFile.set(rootProject.file("LICENSE"))
            includeAllModules = true
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))

            windows {
                menuGroup = "BoitierTube"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "2e637c78-4bbc-4c83-8db5-0ec02e83a5e7"
                iconFile.set(project.file("src/main/resources/boitiertube.ico"))
            }
        }
    }
}

aboutLibraries {
    export {
        outputFile = file("../shared/src/jvmMain/resources/aboutlibraries.json")
        prettyPrint = true
        excludeFields.addAll("organization", "scm", "funding")
    }
}
