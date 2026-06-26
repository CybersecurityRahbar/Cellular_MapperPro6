// ================================================================
// FILE: settings.gradle.kts (جذر المشروع)
// ================================================================

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

rootProject.name = "CellularMapperPro"
include(":app")
