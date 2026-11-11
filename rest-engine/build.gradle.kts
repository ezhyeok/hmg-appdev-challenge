import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.0"
}

// 앱이 dev.shimpyo:rest-engine 좌표로 의존하면 composite build가 이 프로젝트로 대체한다
group = "dev.shimpyo"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// ---- 기획서용 수치 시뮬레이션 (main·test와 분리) ----
// src/sim/kotlin: 엔진 내부 클래스를 그대로 쓰도록 main 컴파일과 연결(associateWith)한다.
// 실행: ./gradlew -p rest-engine runSim  → ../docs/plan/data/engine_curves.json, effect_sim.json
val sim: SourceSet by sourceSets.creating
kotlin.target.compilations.getByName("sim").associateWith(kotlin.target.compilations.getByName("main"))

tasks.register<JavaExec>("runSim") {
    group = "verification"
    description = "엔진 곡선과 효과 몬테카를로를 계산해 docs/plan/data/*.json에 쓴다"
    classpath = sim.runtimeClasspath
    mainClass.set("dev.shimpyo.engine.sim.SimMainKt")
    val repoRoot = rootDir.parentFile
    args(
        repoRoot.resolve("docs/plan/data/analysis.json").absolutePath,
        repoRoot.resolve("docs/plan/data").absolutePath,
        (project.findProperty("simTrips") ?: "5000").toString(),
    )
    jvmArgs("-Xmx3g")
}

// ---- 앱에 넣는 정차 후보 표 (app/src/main/assets/data/stops.bin) ----
// tools/data/build.py가 만든 JSON을 StopRows 규칙으로 변환해 이진 표로 쓴다. 앱은 JSON 대신 이 표를 읽는다.
// JSON을 다시 만들었으면 이것도 다시 실행한다 (StopTableTest가 JSON과 stops.bin이 어긋나면 실패한다)
tasks.register<JavaExec>("buildStopTable") {
    group = "build"
    description = "app/src/main/assets/data/*.json → stops.bin"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.shimpyo.engine.stops.StopTableBuilderKt")
    val data = rootDir.parentFile.resolve("app/src/main/assets/data")
    args(data.absolutePath, data.resolve("stops.bin").absolutePath)
}
