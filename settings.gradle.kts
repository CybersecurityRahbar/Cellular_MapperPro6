pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.0"
        id("org.jetbrains.kotlin.android") version "2.0.0"
        id("kotlin-kapt") version "2.0.0"
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
