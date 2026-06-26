plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt' // لـ Room
}

android {
    namespace 'com.example.cellularmapper'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.cellularmapper"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = '1.8'
    }
    buildFeatures {
        viewBinding true
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'

    // RecyclerView
    implementation 'androidx.recyclerview:recyclerview:1.3.2'

    // Room
    implementation 'androidx.room:room-runtime:2.6.1'
    implementation 'androidx.room:room-ktx:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'

    // osmdroid (للخريطة)
    implementation 'org.osmdroid:osmdroid-android:6.1.18'

    // MPAndroidChart (للرسوم البيانية المتقدمة، اختياري لكن موصى به)
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'

    // Preferences (لإعدادات osmdroid)
    implementation 'androidx.preference:preference-ktx:1.2.1'

    // Lifecycle (لـ viewModel / liveData)
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'

    // تسجيل الدخول (اختياري)
    implementation 'com.jakewharton.timber:timber:5.0.1'
}
