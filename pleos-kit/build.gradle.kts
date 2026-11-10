plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hmg.pleoskit"
    compileSdk = 34
    // AAOS 표준 Car API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    defaultConfig {
        minSdk = 28
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
    // 앱에서 SDK 타입(SeatArea, RouteInfo 등)을 그대로 쓰므로 api로 노출한다
    api("ai.pleos.playground:Vehicle:2.3.2")
    api("ai.pleos.playground:NaviHelper:3.0.5")
    api("ai.pleos.playground:TextToSpeech:3.3.1")
    api("ai.pleos.playground:SpeechToText:3.3.1")
    api("ai.pleos.playground:FusedLocation:1.1.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
