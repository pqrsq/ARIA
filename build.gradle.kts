// Root build file.
// It only declares which plugins the project uses; the versions live in
// gradle/libs.versions.toml and are applied by the modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
