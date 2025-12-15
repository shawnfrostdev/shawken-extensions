// Version as decimal string
version = "0.1"

plugins {
    id("com.android.library")
    id("kotlin-android")
}

android {
    namespace = "com.shawken.uhdmovies"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        targetSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Shawken Plugin API - provided by main app
    compileOnly(project(":app"))  // or however you reference your main app
    
    // Kotlin
    implementation(kotlin("stdlib"))
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // HTML Parsing
    implementation("org.jsoup:jsoup:1.17.2")
}