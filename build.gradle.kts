plugins {
    id("com.android.library") version "9.2.0" apply false
    // Retries the Robolectric compose tests' known timing flakes (snackbar polls are
    // load-sensitive on CI's 2-core runners); a genuinely broken test still fails
    // after the configured retries.
    id("org.gradle.test-retry") version "1.6.4" apply false
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Dokka 2.2.0 is the minimum that supports AGP 9.1+ built-in Kotlin.
    id("org.jetbrains.dokka") version "2.2.0" apply false
}
