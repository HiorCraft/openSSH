import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("openssh.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
}
