plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.example.bmwenettest"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.example.bmwenettest"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.7.3"
    }
}

// v1.7.3 Octane Engine v0.2: empirical AI-95 RPM x MAP baseline
