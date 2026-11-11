buildscript {
    dependencies {
        // AGP 9 usa Kotlin integrado; esta dependencia fija la versión de KGP.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
