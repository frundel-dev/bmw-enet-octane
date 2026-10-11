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
        versionCode = 44
        versionName = "1.7.26"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// v1.7.26: staggered SLOW requests, per-phase timing, experimental mixed-width B+; frozen Fuel Score
