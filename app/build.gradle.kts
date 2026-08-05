import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun propOrDefault(name: String, default: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

// Overridable via -Ptalaria.versionName / -Ptalaria.versionCode (CI sets these from the git tag).
val talariaVersionName = propOrDefault("talaria.versionName", "0.8.4")
val talariaVersionCode = propOrDefault("talaria.versionCode", "8049").toInt()
val hermesApiBaseline = propOrDefault("talaria.hermesApiBaseline", "hermes-v0.19.1")

// Persistent CI upload keystore for Obtainium / GitHub release APKs.
val ciKeystorePath = System.getenv("TALARIA_CI_KEYSTORE")
val ciKeystorePassword = System.getenv("TALARIA_CI_KEYSTORE_PASSWORD")
val ciKeyAlias = System.getenv("TALARIA_CI_KEY_ALIAS") ?: "talaria"
val ciKeyPassword = System.getenv("TALARIA_CI_KEY_PASSWORD")
val useCiSigning =
    !ciKeystorePath.isNullOrBlank() &&
        !ciKeystorePassword.isNullOrBlank() &&
        !ciKeyPassword.isNullOrBlank() &&
        file(ciKeystorePath).isFile

android {
    namespace = "com.hermesgadget.talaria"
    // AGP 9.1.x supports up to API 37; core-ktx 1.19 / lifecycle 2.11 need it.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hermesgadget.talaria"
        // 29+ required by androidx.car.app:app-automotive (Android Auto car apps);
        // Android 9 (API 28) reached EOL in 2022.
        minSdk = 29
        targetSdk = 37
        versionCode = talariaVersionCode
        versionName = talariaVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "HERMES_API_BASELINE", "\"$hermesApiBaseline\"")
        buildConfigField("Boolean", "DEFAULT_TELEMETRY", "false")
    }

    signingConfigs {
        // Local debug stays on the machine debug keystore unless CI overrides below.
        if (useCiSigning) {
            create("ci") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Dev-only package — never ship this via Obtainium (different id + ephemeral certs).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Prefer CI upload key for GitHub/Obtainium; else local keystore.properties.
            signingConfig = when {
                useCiSigning -> signingConfigs.getByName("ci")
                keystorePropertiesFile.exists() -> signingConfigs.getByName("release")
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Built-in Kotlin (AGP 9): jvmTarget follows compileOptions.targetCompatibility
    // (17) by default — no kotlinOptions DSL needed.
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // Fail-closed gate. The AGP-8.9 toolchain baseline (21 pins) was
        // deleted with the AGP 9.1 + compileSdk 37 migration (2026-08-05);
        // any REAL lint finding now fails the build — expect ZERO issues.
        // Version-available checks are informational (a newer release always
        // exists); the toolchain is pinned deliberately in libs.versions.toml.
        disable += setOf(
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "GradleDependency",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Latest stack that fully supports AGP 8.9 + compileSdk 36 (1.19/2.11 need AGP 9.1).
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // View-system Material 3 (XML theme / splash / components).
    implementation(libs.google.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.logging.interceptor)
    implementation(libs.squareup.retrofit)
    implementation(libs.jake.retrofit.serialization)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Android Auto / Automotive OS car app library (sideloaded CarAppService).
    // `app` is the core template library; `app-automotive` adds the AAOS host adapter.
    implementation(libs.car.core)
    implementation(libs.car.automotive)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("assembleSignedRelease") {
    group = "build"
    description = "Assembles a signed release APK when keystore.properties is present."
    dependsOn("assembleRelease")
    doLast {
        val signed = useCiSigning || keystorePropertiesFile.exists()
        val source = when {
            useCiSigning -> "CI signing (useCiSigning)"
            keystorePropertiesFile.exists() -> "keystore.properties ($keystorePropertiesFile)"
            else -> "NONE"
        }
        logger.lifecycle(
            if (signed) "Signed release APK ready under app/build/outputs/apk/release/ (signing: $source)"
            else "Release APK is UNSIGNED (signing: $source). See SETUP.md for signing.",
        )
    }
}
