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
    val groqApiKey: String = project.findProperty("GROQ_API_KEY") as String?
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
