plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

group = "dev.cupthread"
// Overridden by scripts/release-sdk.mjs with -PcupthreadVersion=X.Y.Z.
version = findProperty("cupthreadVersion") ?: "0.1.0"

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
        unitTests.isIncludeAndroidResources = false
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
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
