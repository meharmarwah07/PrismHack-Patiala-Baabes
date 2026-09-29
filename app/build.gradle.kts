import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt") // for Room's annotation processor
}

android {
    namespace = "com.calo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calo"
        minSdk = 26          // AccessibilityService node APIs we rely on need 26+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-hackathon"
    }

    buildFeatures {
        buildConfig = true
    }

    // Groq API key: never hardcode it. Put GROQ_API_KEY=... in
    // local.properties (gitignored) or an env var of the same name;
    // NLUClient reads it from BuildConfig at runtime.
    //
    // BUG FIXED (24 Sep 2026): this used to read ONLY
    // `project.findProperty("GROQ_API_KEY")`, which resolves against
    // Gradle's OWN property system (gradle.properties / -P flags /
    // ORG_GRADLE_PROJECT_* env vars) — NOT local.properties. AGP only
    // auto-loads a handful of its own recognized keys (sdk.dir, ndk.dir)
    // from local.properties; arbitrary custom keys are never exposed to
    // findProperty(). Confirmed on-device: a real GROQ_API_KEY was present
    // in local.properties exactly where this comment says to put it, yet
    // BuildConfig.GROQ_API_KEY was still blank at runtime
    // (`NLUClient: GROQ_API_KEY is not set`) — every VOICE_COMMAND call was
    // silently a guaranteed no-match, not a genuine NLU miss. Fixed by
    // actually loading local.properties here, same as AGP does internally
    // for sdk.dir.
    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { load(it) }
        }
    }
    val groqApiKey: String = localProperties.getProperty("GROQ_API_KEY")
        ?: project.findProperty("GROQ_API_KEY") as String?
        ?: System.getenv("GROQ_API_KEY")
        ?: ""

    defaultConfig {
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Hackathon submission: judges sideload the release APK directly, so it
    // just needs to be signed, not distributed via Play Store. Reusing the
    // debug signing config for release is standard for this case — no
    // dedicated release keystore needed.
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // NLUClient calls android.util.Log; returnDefaultValues makes that a
    // harmless no-op under plain JVM unit tests instead of throwing, so
    // NLUClientTest doesn't need Robolectric just to exercise error paths.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":domain"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":domain"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
