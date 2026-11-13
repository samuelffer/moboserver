plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.moboserver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moboserver.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.7"

        // Minecraft Bedrock is 64-bit only, so shipping a single ABI keeps the
        // APK small without limiting any supported device.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/moboserver-signing.p12")
            storePassword = "moboserver2026"
            keyAlias = "moboserver"
            keyPassword = "moboserver2026"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            // R8 stays off on purpose: the app is small, and disabling it
            // removes a whole class of runtime surprises on unusual devices.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // The Go server binary is shipped as a native library so Android lets
        // the app execute it. Legacy packaging extracts the libs to the
        // executable native library directory of the APK.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "kotlin/**",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

/**
 * The embedded Minecraft server is compiled from ./server by the build
 * workflow. Failing early here is much better than shipping an APK that cannot
 * start a server at all.
 */
val checkServerBinary = tasks.register("checkServerBinary") {
    doLast {
        val binary = file("src/main/jniLibs/arm64-v8a/libmobosrv.so")
        if (!binary.exists() || binary.length() < 1_000_000L) {
            throw GradleException(
                "Servidor embutido ausente em ${binary.path}. Compile o servidor Go antes: " +
                    "make server (veja o README).",
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkServerBinary)
}
