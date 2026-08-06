# task-queue

IntelliJ 플러그인. Claude Code 작업을 칸반 보드에 쌓아두고 IDE 터미널 탭에서 실행한다.
마켓플레이스 표시 이름은 **Claude Task Queue**, 플러그인 ID 는 `dev.roon.taskqueue`.

## 빌드할 때 버전을 올린다

**기능 수정·버그 수정을 빌드할 때마다 `gradle.properties` 의 `pluginVersion` 패치를 올린다.**
같이 할 것:

1. `pluginVersion` 패치 +1 (예: `0.6.5` → `0.6.6`)
2. `src/main/resources/META-INF/plugin.xml` 의 `<change-notes>` 맨 위에 그 버전 항목 추가
   — 사용자가 읽는 글이다. 내부 구현이 아니라 **달라지는 경험**을 쓴다
3. `./gradlew build buildPlugin`

이유:
- 마켓플레이스는 **같은 버전을 두 번 받지 않는다.** 올리지 않으면 업로드가 거부된다
- 같은 번호의 zip 이 내용만 다르면 어느 걸 설치했는지 추적이 불가능해진다

### 검증용 빌드에는 기능명을 붙인다

아직 올릴 게 아니라 **직접 설치해 확인하려고** 만드는 빌드는 `0.6.7-terminal-enqueue` 처럼
패치 뒤에 기능명을 붙인다. zip 이름에 그대로 들어가서 여러 개가 쌓여도 어느 게 뭔지 보인다.

마켓플레이스에 올릴 때는 **접미사를 떼고** `0.6.7` 로 다시 빌드한다 — 접미사가 붙은 채
올라가면 그 문자열이 사용자에게 버전으로 보인다.

**이전 버전 zip 은 지우지 않는다** — `clean` 을 붙이지 말고 `build buildPlugin` 만 쓴다.
되돌려 비교할 일이 생긴다.

`pluginId` 는 절대 바꾸지 않는다. 바꾸면 다른 플러그인이 되어 기존 사용자가 업데이트를 받지 못한다.

## 빌드 환경

Gradle 은 JVM 17+ 에서 돌아야 한다. 셸 기본 JDK 가 낮으면:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew build buildPlugin
```

레포의 `gradle.properties` 에 `org.gradle.java.home` 을 박지 않는다 — 공개 레포라
다른 사람 환경에서 빌드가 깨진다.

## 검증

- `./gradlew build` 로 테스트를 **실제로 돌린 결과**를 보고한다. `UP-TO-DATE` 로 넘어갔으면
  돌린 것이 아니다 — 필요하면 `--rerun-tasks`
- 터미널·세션 동작은 **추측하지 말고 로그로 확인한다.** 진행 상황을 `idea.log` 로 흘리는
  임시 진단을 넣고 `runIde` 로 재현하는 방식이 이 레포에서 반복적으로 통했다
- `runIde` 샌드박스에서는 claude 세션의 전사(jsonl)가 쓰이지 않는다 — 부모 프로세스의
  `CLAUDE_CODE_CHILD_SESSION` 환경변수를 물려받기 때문이다. 전사를 읽는 기능(컨텍스트
  점유율, 완료 판정)은 샌드박스로 검증할 수 없다. zip 을 실제 IDE 에 설치해서 봐야 한다

## 작업 계획

`private/` 아래에 계획 파일을 먼저 쓴다 (gitignore 대상). 파일 1개 수준의 작은 작업은 생략 가능.
