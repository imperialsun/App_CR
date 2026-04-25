import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun loadEnvFile(file: File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val normalized = line.removePrefix("export ").trim()
            val separator = normalized.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = normalized.substring(0, separator).trim()
            val value = normalized.substring(separator + 1).trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (key.isBlank()) null else key to value
        }
        .toMap()
}

fun String.asBuildConfigStringLiteral(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

val projectEnv = loadEnvFile(rootProject.file(".env.production"))
val backendBaseUrl = projectEnv["BACKEND_BASE_URL"]?.trim().orEmpty()
if (backendBaseUrl.isBlank()) {
    throw GradleException("Missing BACKEND_BASE_URL in App/.env.production")
}

android {
    namespace = "com.demeter.speech"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.demeter.speech"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "BACKEND_BASE_URL", backendBaseUrl.asBuildConfigStringLiteral())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
