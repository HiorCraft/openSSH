plugins {
    id("openssh.kotlin-library")
}

dependencies {
    api(project(":core-ssh"))
    api(libs.jediterm.core)
    api(libs.jediterm.ui)
    implementation(libs.pty4j)
}

dependencies {
    testImplementation(testFixtures(project(":core-ssh")))
}
