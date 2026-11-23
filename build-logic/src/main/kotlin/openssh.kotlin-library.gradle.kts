import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).orElseThrow { IllegalStateException("Fehlt im Version Catalog: $alias") }

group = "dev.hiorcraft.openssh"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    "implementation"(lib("kotlinx-coroutines-core"))

    "testImplementation"(platform(lib("junit-bom")))
    "testImplementation"(lib("junit-jupiter"))
    "testImplementation"(kotlin("test-junit5"))
    "testImplementation"(lib("kotlinx-coroutines-test"))
    "testRuntimeOnly"(lib("junit-platform-launcher"))
    "testRuntimeOnly"(lib("slf4j-simple"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
