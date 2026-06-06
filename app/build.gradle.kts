plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.nyandroid.terminal"
    compileSdk = 36

    // Pinned for reproducible native (CMake) builds in CI; keep in sync with
    // the NDK installed by .github/workflows/build.yml.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.nyandroid.terminal"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"

        // PoC targets Pixel (arm64) only. Add more ABIs when broadening device support.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Build the native PTY helper as a shared lib loaded via JNI.
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // PoC: keep release unsigned/unminified for now.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle (sshj transitive dep) ships duplicate OSGI manifests.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.sshj)
    implementation(libs.slf4j.nop)
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
