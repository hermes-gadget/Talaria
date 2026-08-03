import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun propOrDefault(name: String, default: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

// Overridable via -Ptalaria.versionName / -Ptalaria.versionCode (CI sets these from the git tag).
val talariaVersionName = propOrDefault("talaria.versionName", "0.6.0")
val talariaVersionCode = propOrDefault("talaria.versionCode", "600").toInt()
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
    // AGP 8.9.x supports up to compileSdk 36; keep aligned with Material3 / activity 1.12.x
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermesgadget.talaria"
        // 29+ required by androidx.car.app:app-automotive (Android Auto car apps);
        // Android 9 (API 28) reached EOL in 2022.
        minSdk = 29
        targetSdk = 36
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
    kotlinOptions {
        jvmTarget = "17"
    }
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
}

dependencies {
    // Latest stable Compose BOM (Material3 1.4.x + adaptive suite)
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Latest stack that fully supports AGP 8.9 + compileSdk 36 (1.19/2.11 need AGP 9.1)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.3")

    // View-system Material 3 (XML theme / splash / components)
    implementation("com.google.android.material:material:1.14.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.3")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Android Auto / Automotive OS car app library (sideloaded CarAppService).
    // `app` is the core template library; `app-automotive` adds the AAOS host adapter.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-automotive:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.register("assembleSignedRelease") {
    group = "build"
    description = "Assembles a signed release APK when keystore.properties is present."
    dependsOn("assembleRelease")
    doLast {
        if (!keystorePropertiesFile.exists()) {
            logger.lifecycle(
                "No keystore.properties — release APK is unsigned. See SETUP.md for signing.",
            )
        } else {
            logger.lifecycle("Signed release APK ready under app/build/outputs/apk/release/")
        }
    }
}
