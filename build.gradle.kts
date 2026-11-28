// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    // AGP 9.0 has a runtime dependency on KGP 2.2.10. Declare a newer KGP here so the
    // compiler can read dependencies built with Kotlin 2.4 (for example adb-kt).
    // See https://developer.android.com/build/migrate-to-built-in-kotlin
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.ksp) apply false
}
