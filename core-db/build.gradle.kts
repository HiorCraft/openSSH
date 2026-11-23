plugins {
    id("openssh.kotlin-library")
}

dependencies {
    api(project(":core-ssh"))
    implementation(libs.hikari)
    implementation(libs.lettuce)
    runtimeOnly(libs.jdbc.mariadb)
    runtimeOnly(libs.jdbc.postgresql)
    implementation(libs.jdbc.sqlite)

    testImplementation(testFixtures(project(":core-ssh")))
}
