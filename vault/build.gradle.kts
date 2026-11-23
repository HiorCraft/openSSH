plugins {
    id("openssh.kotlin-library")
}

dependencies {
    implementation(libs.jna)
    implementation(libs.jna.platform)
}
