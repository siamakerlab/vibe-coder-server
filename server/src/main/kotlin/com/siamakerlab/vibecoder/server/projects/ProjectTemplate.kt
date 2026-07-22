package com.siamakerlab.vibecoder.server.projects

/**
 * v0.18.0 — 미리 정의된 Android 프로젝트 시작 템플릿.
 *
 * 본 카탈로그는 신규 프로젝트 생성 폼의 "Template" 드롭다운에 노출. 사용자가
 * 선택 시 `ProjectService.register` 가 빈 폴더 + CLAUDE.md 만 만드는 게 아니라,
 * 템플릿의 **starter prompt** 를 콘솔에 자동 입력해 Claude 가 그 패턴대로
 * scaffolding 하도록 유도. (실제 파일 scaffold 는 Claude 가 함 — 우리는 prompt 만
 * 제공.)
 *
 * 왜 prompt-only?
 *   1. Claude 가 매번 stable 한 코드를 만들도록 prompt 를 cherry-pick 한 게 더 안정적.
 *   2. 템플릿 자체에 파일을 박으면 Gradle 버전 / minSdk / Material 버전이 시간 따라
 *      stale 됨. Claude 가 "최신 안정" 으로 만들면 알아서 따라옴.
 *   3. 우리는 build tool 만 책임, code generation 은 Claude — 분업 명확.
 */
data class ProjectTemplate(
    val id: String,
    val title: String,
    val description: String,
    val starterPrompt: String,
)

object ProjectTemplates {
    val all: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "empty",
            title = "빈 프로젝트 (기본)",
            description = "Claude 가 처음부터 scaffolding. 자유도 최대, 시작은 사용자가 직접 prompt.",
            starterPrompt = "",   // 빈 prompt = 자동 입력 없음
        ),
        ProjectTemplate(
            id = "compose-basic",
            title = "Compose 기본 (Material3)",
            description = "Jetpack Compose + Material3, 단일 Activity, 'Hello' 화면 1개.",
            starterPrompt = """
                Android 프로젝트를 처음부터 만들어줘.
                - Kotlin + Jetpack Compose + Material3 + 단일 Activity.
                - minSdk 26, targetSdk 35.
                - 패키지명은 build.gradle.kts 의 applicationId 사용.
                - 시작 화면: MainActivity 에서 "Hello, ${'$'}{앱이름}" 텍스트를 가운데 표시.
                - Gradle wrapper 는 /home/vibe/.local/gradle 의 설치 버전과 정렬.
                만든 뒤 assembleDebug 가 통과하는지 확인해줘.
            """.trimIndent(),
        ),
        ProjectTemplate(
            id = "compose-mvvm-hilt",
            title = "Compose + MVVM + Hilt",
            description = "Compose + Material3 + Hilt DI + MVVM Repository 패턴, 샘플 화면 1개.",
            starterPrompt = """
                Android 프로젝트를 다음 스택으로 scaffolding.
                - Kotlin + Jetpack Compose + Material3.
                - Hilt for DI (Application 클래스 + @HiltAndroidApp + @HiltViewModel).
                - MVVM: ViewModel 이 Repository 를 주입받음. Repository 는 in-memory 더미.
                - 단일 Activity + Compose Navigation (1 destination).
                - minSdk 26, targetSdk 35. Gradle wrapper 는 호스트 설치 버전과 정렬.
                - 샘플 화면: Counter (ViewModel state hoisting + + / − 버튼).
                만든 뒤 assembleDebug 가 통과하는지 확인해줘.
            """.trimIndent(),
        ),
        ProjectTemplate(
            id = "compose-mvvm-room",
            title = "Compose + MVVM + Hilt + Room",
            description = "위 +Hilt + Room (간단한 TODO 리스트).",
            starterPrompt = """
                Android 프로젝트를 다음 스택으로 scaffolding.
                - Kotlin + Jetpack Compose + Material3 + Hilt + Room.
                - MVVM: Repository 가 RoomDao 위에. ViewModel 에 Flow<List<Todo>>.
                - 단일 Activity + Compose Navigation.
                - 샘플 화면: 간단한 TODO 추가/삭제. 데이터는 Room 에 영구.
                - minSdk 26, targetSdk 35. Gradle wrapper 는 호스트 설치 버전과 정렬.
                만든 뒤 assembleDebug 가 통과하는지 확인해줘.
            """.trimIndent(),
        ),
        ProjectTemplate(
            id = "wear-os",
            title = "Wear OS — Compose",
            description = "Wear OS 시계용. Compose for Wear OS, Scaffold + ScalingLazyColumn.",
            starterPrompt = """
                Wear OS 프로젝트를 scaffolding.
                - Compose for Wear OS (androidx.wear.compose:compose-material3 또는 material).
                - Manifest 에 watch face 또는 standard wear app 으로 선언 (standard 가 기본).
                - 시작 화면: ScalingLazyColumn 으로 3개 placeholder item.
                - minSdk 30, targetSdk 35.
                만든 뒤 assembleDebug 가 통과하는지 확인해줘.
            """.trimIndent(),
        ),
        ProjectTemplate(
            id = "android-tv",
            title = "Android TV — Compose",
            description = "TV 용. androidx.tv.material3, leanback navigation pattern.",
            starterPrompt = """
                Android TV 프로젝트를 scaffolding.
                - androidx.tv.material3 (TV용 Compose).
                - Banner row + Featured row 두 개의 horizontal lazy row.
                - DPad navigation focus 동작 확인.
                - minSdk 30, targetSdk 35. Manifest 에 LEANBACK + TV 카테고리 선언.
                만든 뒤 assembleDebug 가 통과하는지 확인해줘.
            """.trimIndent(),
        ),
        ProjectTemplate(
            id = "iphone-swiftui-basic",
            title = "iPhone - SwiftUI 기본",
            description = "SwiftUI iPhone 앱. Xcode project, ContentView, XCTest 골격을 기준으로 첫 화면을 구현.",
            starterPrompt = """
                iPhone SwiftUI 프로젝트를 점검하고 첫 화면을 구현해줘.
                - Swift/SwiftUI/Xcode 규칙만 사용하고 Kotlin/Flutter/Android Gradle 파일은 만들지 마.
                - bundle id 는 현재 CLAUDE.md 의 Bundle ID 를 기준으로 유지.
                - ContentView 를 iPhone 세로 화면 기준으로 정돈하고 accessibility label 을 포함.
                - XCTest scaffold 가 있으면 starter smoke test 를 보강.
                - 가능한 경우 xcodebuild -scheme <scheme> -destination 'generic/platform=iOS Simulator' build 로 검증해줘.
                - Linux 컨테이너 단독이면 MacBook local 또는 macOS agent 가 필요하다고 명확히 보고해줘.
            """.trimIndent(),
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}
