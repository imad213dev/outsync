plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = "dev.outsync"
    version = "0.1.0-SNAPSHOT"
}
