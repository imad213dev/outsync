plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val onJitPack = System.getenv("JITPACK") != null

allprojects {
    group = if (onJitPack) "com.github.imad213dev.outsync" else "io.github.imad213dev"
    version = System.getenv("VERSION").takeIf { onJitPack && !it.isNullOrBlank() } ?: "0.1.0"
}
