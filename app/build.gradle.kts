import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// release 서명: 저장소 밖에 둔 keystore.properties(루트, git 제외)를 읽는다. 없으면 release는 서명 없이 만들어진다
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 값이 같은 화면은 다시 그리지 않는다(강한 건너뛰기). 엔진 출력 타입(불변 data class)과 읽기 전용 컬렉션은 안정 타입으로 본다.
// 예전에는 서비스가 상태를 매초 갱신할 때마다 화면 전체가 다시 구성됐다(2026-11-11 gfxinfo: 프레임 77% 지연)
composeCompiler {
    enableStrongSkippingMode.set(true)
    stabilityConfigurationFile.set(project.layout.projectDirectory.file("compose-stability.conf"))
}

android {
    namespace = "dev.shimpyo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.shimpyo.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Pleos SDK가 리플렉션을 쓰는지 확인하지 않아서 축소는 끈다
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // assets/data의 JSON(공개 데이터 원본, tools/data/README.md)은 APK에 넣지 않는다. 앱은 같은 내용으로 만든
    // stops.bin만 읽는다 (rest-engine buildStopTable). 기본 무시 목록에 *.json을 더한 것
    androidResources {
        ignoreAssetsPattern = "!*.json:!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
        // stops.bin은 압축하지 않는다: 앱이 압축 풀기 없이 메모리 매핑으로 읽는다 (StopAssets)
        noCompress += "bin"
    }
}

dependencies {
    implementation(project(":pleos-kit"))
    // 판단 로직 (includeBuild("rest-engine")가 이 좌표를 대체한다)
    implementation("dev.shimpyo:rest-engine:0.1.0")
    // Pleos LLM SDK: 공모전 안내에는 없고 API 레퍼런스만 있다. 에뮬레이터에서 동작하는지 확인 중
    implementation("ai.pleos.playground:LLM:2.1.3.2")

    // Compose. compileSdk 34에 맞는 버전 (Compose 1.7, activity 1.9, lifecycle 2.8)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // JVM 단위 테스트 (안드로이드 API를 쓰지 않는 코드만: CoachStore 등)
    testImplementation("junit:junit:4.13.2")
}
