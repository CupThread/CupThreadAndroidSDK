plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.dokka")
    id("maven-publish")
    id("org.gradle.test-retry")
}

group = "dev.cupthread"
// Overridden by scripts/release-sdk.mjs with -PcupthreadVersion=X.Y.Z.
version = findProperty("cupthreadVersion") ?: "0.1.0"

// Lowercase-kebab only: Dokka derives the published module directory from
// this value, and spaces or camelCase would leak into URLs.
dokka {
    moduleName.set("cupthread-android-sdk")
    dokkaSourceSets.configureEach {
        // Module/package overview rendered on the docs landing page.
        includes.from("overview.md")
        sourceLink {
            localDirectory.set(file("src/main/java"))
            remoteUrl("https://github.com/CupThread/CupThreadAndroidSDK/blob/main/feedback/src/main/java")
            remoteLineSuffix.set("#L")
        }
        externalDocumentationLinks {
            create("android") {
                url.set(uri("https://developer.android.com/reference/kotlin/"))
            }
            create("coroutines") {
                url.set(uri("https://kotlinlang.org/api/kotlinx.coroutines/"))
            }
        }
    }
}

val copyDokkaScreenshots by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("docs/images")) {
        include("*.jpg")
    }
    into(layout.buildDirectory.dir("dokka/html/images"))
}

tasks.named("dokkaGenerateHtml") {
    finalizedBy(copyDokkaScreenshots)
}

android {
    namespace = "dev.cupthread.feedback"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric needs merged resources/manifest to host the Compose test activity.
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Compose UI regression tests run on the JVM through Robolectric so CI covers the bundled
    // screens without an emulator (issue #142).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.15.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "dev.cupthread"
                artifactId = "feedback"
                version = project.version.toString()
                pom {
                    name = "CupThread Android SDK"
                    description = "Jetpack Compose feedback surfaces for CupThread: roadmap, " +
                        "What's New, feature requests and in-app feedback."
                    url = "https://cupthread.com"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "https://opensource.org/licenses/MIT"
                            distribution = "repo"
                        }
                    }
                }
            }
        }
        repositories {
            // Staging target used by scripts/release-sdk.mjs; its contents are
            // uploaded verbatim to cdn.cupthread.com/maven.
            maven {
                name = "Cdn"
                url = uri(findProperty("cupthreadRepoDir") ?: layout.buildDirectory.dir("cdn-maven"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    // The Robolectric compose tests poll for snackbar/pill state with a frame-clock
    // pump whose visible window is load-sensitive on CI's 2-core runners (the same
    // test fails on one run and passes on the next, locally and on main). Two retries
    // keep a timing flake from failing the branch while a genuinely broken test still
    // fails the build after its retries.
    retry {
        maxRetries = 2
    }
}
