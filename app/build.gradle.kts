plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // 앱 이름이 정해지면 namespace와 applicationId를 바꾼다
    namespace = "dev.hmg.pleosapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.hmg.pleosapp"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":pleos-kit"))
}
