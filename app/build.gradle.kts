plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.selfxposed"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.selfxposed"
        minSdk = 26
        targetSdk = 28
        versionCode = 11
        versionName = "2.2.0-beta1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
