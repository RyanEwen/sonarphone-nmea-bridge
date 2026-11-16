plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.diffplug.spotless") version "8.11.0"
}

// One formatter for Kotlin sources and Gradle scripts (STYLE-8, D-092).
// `spotlessApply` rewrites files; `spotlessCheck` fails on any difference.
// The code style is set here as well as in .editorconfig because the builder
// container mounts only android/, where the root .editorconfig is not visible.
val ktlintStyle = mapOf("ktlint_code_style" to "intellij_idea")

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(ktlintStyle)
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(ktlintStyle)
    }
}
