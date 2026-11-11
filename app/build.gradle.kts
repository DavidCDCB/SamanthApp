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
        versionCode = 23
        versionName = "2.2.4-SamanthApp"
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

    testImplementation(libs.junit)
}
