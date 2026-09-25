plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yuri.corridaideal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuri.corridaideal"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
