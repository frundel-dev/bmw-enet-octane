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
        applicationId = "com.example.bmwenettest.octane2"
        manifestPlaceholders["appLabel"] = "BMW OCTANE"
        minSdk = 26
        targetSdk = 35
        versionCode = 224
        versionName = "2.0.1-experimental"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("octane") {
            dimension = "edition"
            // Preserve the existing package name and installed user data.
        }
        create("lab") {
            dimension = "edition"
            applicationIdSuffix = ".lab"
            manifestPlaceholders["appLabel"] = "BMW OCTANE LAB"
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(project(":core:data"))
    implementation(project(":core:diagnostics"))
    implementation(project(":feature:analysis"))
    add("labImplementation", project(":feature:benchmark"))
    testImplementation("junit:junit:4.13.2")
}

// v1.7.27: parked-only DME adaptation/DTC history read; frozen Fuel Score
