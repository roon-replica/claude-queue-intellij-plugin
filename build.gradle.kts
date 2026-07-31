plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // IC 는 2025.3(253)부터 별도 배포가 없다 — 통합 아티팩트를 쓴다
        intellijIdea(providers.gradleProperty("platformVersion"))
        // 터미널 실행 모드용 — IDEA 에 기본 번들된 플러그인
        bundledPlugin("org.jetbrains.plugins.terminal")
        // 플랫폼 테스트 프레임워크 미사용 — 순수 로직 테스트라 JUnit5 만으로 충분
        // (추가하면 com.intellij.tests.JUnit5TestSessionListener 가 테스트 실행을 막는다)
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 플랫폼 플러그인이 테스트 태스크에 자기 설정을 주입해 JUnit4 클래스를 요구한다
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    // Java 폼/@NotNull 계측 미사용 — 끄면 빌드가 짧아진다
    instrumentCode = false

    /**
     * 신 터미널 API 가 있는 버전만 정적 검증한다.
     *
     * 그 API 는 253 부터 있고(검증기 확인), 그 아래(243/251/252)에서는 **패키지 자체가 없어**
     * 검증기가 참조를 문제로 찍는다. [TerminalEngines] 의 `Class.forName` 가드가 그 경로를
     * 타지 않게 막지만 정적 분석은 그것을 보지 못한다 — 경고를 끄면 진짜 문제까지 가려지므로
     * 대상에서 뺀다. 하한은 `runIde243` 으로 직접 띄워 확인했다(로드·터미널 기능 정상).
     *
     * "있는데 모양이 다른" 구간은 없다 — 문제 보고가 전부 `Package is not found` 였다.
     */
    pluginVerification {
        ides {
            create("IU", "2025.3")
            create("IU", "2026.1")
        }
    }

    pluginConfiguration {
        id = "dev.roon.taskqueue"
        name = "Task Queue"
        version = providers.gradleProperty("pluginVersion")
        vendor {
            name = "roon"
            email = "think-roon@naver.com"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild 미지정 — IDE 업그레이드로 사용 불가가 되는 것을 방지 (설계 5.3.4)
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

/**
 * 지원 하한(2024.3) 샌드박스.
 *
 * 신 터미널 API 는 2026.1 에만 있고 [TerminalEngines] 가 `Class.forName` 으로 가려 쓴다.
 * 그 가드가 실제로 통하는지 — 구버전에서 NoClassDefFoundError 없이 Classic 경로로
 * 도는지 — 는 여기서 띄워봐야 알 수 있다. 검증기는 정적 분석이라 가드를 보지 못한다.
 */
intellijPlatformTesting.runIde.register("runIde243") {
    type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity
    version = "2024.3"
}
