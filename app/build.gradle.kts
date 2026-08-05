import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.let { value ->
    value.toIntOrNull()?.takeIf { it > 0 }
        ?: error("releaseVersionCode must be a positive integer")
}
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull?.also { value ->
    require(value.isNotBlank()) { "releaseVersionName must not be blank" }
}
val releaseSigningValues = mapOf(
    "keystore" to System.getenv("MOBILE_PI_KEYSTORE_FILE"),
    "storePassword" to System.getenv("MOBILE_PI_KEYSTORE_PASSWORD"),
    "keyAlias" to System.getenv("MOBILE_PI_KEY_ALIAS"),
    "keyPassword" to System.getenv("MOBILE_PI_KEY_PASSWORD"),
)
val configuredReleaseSigningValues = releaseSigningValues.values.count { !it.isNullOrBlank() }
check(configuredReleaseSigningValues == 0 || configuredReleaseSigningValues == releaseSigningValues.size) {
    "Release signing requires all MOBILE_PI_KEYSTORE_* and MOBILE_PI_KEY_* environment variables"
}
val releaseSigningEnabled = configuredReleaseSigningValues == releaseSigningValues.size

android {
    namespace = "dev.mobilepi"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mobilepi"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues["keystore"]))
                storePassword = requireNotNull(releaseSigningValues["storePassword"])
                keyAlias = requireNotNull(releaseSigningValues["keyAlias"])
                keyPassword = requireNotNull(releaseSigningValues["keyPassword"])
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":runtime:pi"))
    implementation(project(":runtime:terminal-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
}
