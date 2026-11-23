plugins {
    id("openssh.kotlin-library")
    `java-test-fixtures`
}

dependencies {
    api(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)

}

dependencies {
    testFixturesApi(libs.sshd.core)
    testFixturesApi(libs.sshd.sftp)
    testFixturesApi(libs.kotlinx.coroutines.core)
}

// Startet den eingebetteten Test-SSH-Server für manuelle Tests der App (Port 2222)
tasks.register<JavaExec>("runTestServer") {
    group = "application"
    description = "Startet einen lokalen Test-SSH/SFTP-Server auf 127.0.0.1:2222 (tester / s3cret!)"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("dev.hiorcraft.openssh.ssh.testing.TestSshServerMainKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
