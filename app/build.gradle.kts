plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cdcb.samanthapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cdcb.samanthapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 56
        versionName = "2.8.4-SamanthApp"

        // Vosk trae librerías nativas; el teléfono es arm64 y el APK se
        // mantiene razonable para sideload.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la keystore de debug (misma firma que la app ya
            // instalada) para poder instalar/actualizar sin perder datos.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)

    // Wake word offline con Vosk (incluye JNA y las librerías nativas).
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation(libs.junit)
}
