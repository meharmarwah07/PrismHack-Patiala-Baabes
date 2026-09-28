// Pure Kotlin/JVM module. Deliberately has NO Android dependency, and no
// dependency on :app. That's the whole point: the credential gate rules,
// slot substitution, replay sequencing, and NLU prompt/response logic all
// live here so they run under plain `gradle test` -- no emulator, no device,
// no Android SDK required. Only the code that actually has to touch
// AccessibilityNodeInfo, Room, or SpeechRecognizer lives in :app.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // Was 17; relaxed to 21 to match the JDK already installed locally,
    // avoiding Gradle's network-dependent toolchain auto-download.
    jvmToolchain(21)
}
