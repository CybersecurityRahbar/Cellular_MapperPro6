// Top-level build file
plugins {
    // not needed for root
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // لـ MPAndroidChart
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
