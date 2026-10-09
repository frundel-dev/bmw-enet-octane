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
        versionCode = 34
        versionName = "1.7.16"
    }
}

// v1.7.16: readable dashboard, rapid Wi-Fi unblock recovery, full idle telemetry
