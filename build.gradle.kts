plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    id("io.realm.kotlin") version "1.11.0" apply false
}

buildscript {
    dependencies {
    }
}

// Define project-wide properties
extra["kotlinVersion"] = "1.9.0"
extra["coroutinesVersion"] = "1.7.3"
extra["lifecycleVersion"] = "2.6.2"
extra["coreKtxVersion"] = "1.13.1"
extra["appCompatVersion"] = "1.7.0"
extra["materialVersion"] = "1.12.0"
extra["constraintLayoutVersion"] = "2.1.4"
extra["navigationVersion"] = "2.7.5"