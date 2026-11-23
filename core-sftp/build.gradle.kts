plugins {
    id("openssh.kotlin-library")
}

dependencies {
    api(project(":core-ssh"))

    testImplementation(testFixtures(project(":core-ssh")))
}
