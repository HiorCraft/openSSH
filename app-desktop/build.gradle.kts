import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("openssh.compose-app")
}

dependencies {
    implementation(project(":core-ssh"))
    implementation(project(":core-sftp"))
    implementation(project(":core-db"))
    implementation(project(":vault"))
    implementation(project(":terminal"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna.platform)
    runtimeOnly(libs.slf4j.simple)
}

compose.desktop {
    application {
        mainClass = "dev.hiorcraft.openssh.app.MainKt"
        // Laufzeit und jpackage kommen aus dem JDK-21-Toolchain, unabhängig vom JDK, mit dem Gradle läuft
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "OpenSSH"
            packageVersion = "1.0.0"
            description = "SSH-, SFTP- und Datenbank-Client für Windows 11"
            vendor = "OpenSSH"
            copyright = "© 2026 OpenSSH"
            // Module der Laufzeit, die jlink in das gepackte JRE übernimmt
            modules("java.sql", "java.naming", "java.management", "jdk.crypto.ec", "jdk.unsupported", "java.security.jgss")

            windows {
                menu = true
                menuGroup = "OpenSSH"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6c1f6b5e-3b0e-4b8f-9a55-2f4d8d3c7a21"
                iconFile.set(project.file("src/main/resources/icons/app.ico"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
