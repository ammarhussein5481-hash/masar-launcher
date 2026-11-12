plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: a key from the environment if CI provides one, otherwise the public
// sideload key in signing/ (see signing/README.md). Either way every build has a
// stable signature, so updates install over the previous version.
val envKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "cloud.ammarhussein.masar"
    compileSdk = 35

    defaultConfig {
        applicationId = "cloud.ammarhussein.masar"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
    }

    signingConfigs {
        create("release") {
            if (envKeystore != null) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = rootProject.file("signing/sideload.jks")
                storePassword = "masar-sideload"
                keyAlias = "masar"
                keyPassword = "masar-sideload"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Live view from a Wi-Fi dash cam (RTSP). The only libraries Masar uses.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
}
