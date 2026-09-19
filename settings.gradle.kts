pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "calo"

// :domain — pure Kotlin/JVM, zero Android imports. This is what makes the
// gate/slot/NLU logic unit-testable on the JVM without an emulator.
// :app — the actual Android app: AccessibilityService, Room, SpeechRecognizer,
// networking. Depends on :domain for the shared data models and rules.
include(":domain", ":app")
