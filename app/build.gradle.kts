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
        versionCode = 38
        versionName = "1.7.20"
    }
}

// v1.7.20: read-only UDS 0x22 DME parameter compatibility survey and raw response capture
