plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
}

val onJitPack = System.getenv("JITPACK") != null

allprojects {
    group = if (onJitPack) "com.github.imad213dev.outsync" else "io.github.imad213dev"
    version = System.getenv("VERSION").takeIf { onJitPack && !it.isNullOrBlank() }
        ?: providers.gradleProperty("VERSION_NAME").get()
}

subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            if (!onJitPack) publishToMavenCentral(automaticRelease = true)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
            pom {
                name.set(project.name)
                description.set("Offline-first sync for Android and Kotlin Multiplatform: local-first writes, a persistent outbox, automatic sync and conflict resolution.")
                inceptionYear.set("2026")
                url.set("https://github.com/imad213dev/outsync")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("imad213dev")
                        name.set("IMAD 213")
                        url.set("https://github.com/imad213dev")
                    }
                }
                scm {
                    url.set("https://github.com/imad213dev/outsync")
                    connection.set("scm:git:git://github.com/imad213dev/outsync.git")
                    developerConnection.set("scm:git:ssh://git@github.com/imad213dev/outsync.git")
                }
            }
        }
    }
}
