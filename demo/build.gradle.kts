plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.cupthread.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cupthread.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Production by default; override for local dev with
        // ./gradlew assembleDebug -PcupthreadBaseUrl=http://10.0.2.2:8787 -PcupthreadAppKey=...
        buildConfigField(
            "String",
            "CUPTHREAD_BASE_URL",
            "\"${findProperty("cupthreadBaseUrl") ?: "https://api.cupthread.com"}\""
        )
        buildConfigField(
            "String",
            "CUPTHREAD_APP_KEY",
            "\"${findProperty("cupthreadAppKey") ?: "app_demo_placeholder"}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":feedback"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
