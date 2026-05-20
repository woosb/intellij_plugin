# Project context for Claude Code

> Claude Code가 새 세션 시작 시 자동으로 읽는 파일입니다.
> 다른 머신에서 작업하더라도 동일한 컨텍스트로 작업할 수 있도록 합니다.

## 무엇을 하는 프로젝트인가

DataGrip / IntelliJ IDEA Ultimate에서 Oracle 딕셔너리(테이블 · 뷰 · 프로시저 · 펑션 · 패키지 · 세션 · 락)를 탭 다이얼로그와 Tool Window로 빠르게 보여주는 플러그인. 사용자가 보는 화면은 한국어/영문이지만, 코드/커밋/PR은 영문 conventional commit 스타일을 유지한다.

## 빌드 환경

| 항목 | 값 |
|------|-----|
| Kotlin | 2.2.0 |
| Gradle | 8.10 (wrapper 포함) |
| IntelliJ Platform | `org.jetbrains.intellij.platform` 2.6.0 |
| 빌드 JDK | **Temurin 21** — `~/.gradle/gradle.properties`의 `org.gradle.java.home`로 지정 (프로젝트 파일 아닌 **사용자 home**) |
| 대상 IDE | IntelliJ IDEA Ultimate **2024.3.5+** (`sinceBuild=243`) + 번들 `com.intellij.database` |

> JDK 25 / JBR 25로는 Kotlin 컴파일러의 `JavaVersion.parse()`가 실패한다. Temurin 21 경로를 그대로 둘 것.

## 자주 쓰는 명령

```bash
./gradlew runIde        # 샌드박스 IDE 띄워서 플러그인 동작 확인
./gradlew buildPlugin   # build/distributions/*.zip 생성 (Marketplace 업로드용)
./gradlew compileKotlin # 빠른 컴파일 검증
./scripts/ship.sh       # 현재 브랜치 push → PR → squash merge → 원격 브랜치 삭제
```

## 코드 구조 요약

```
src/main/kotlin/com/github/wooju/oracleinspector/
├── OracleInspectorBundle.kt         # i18n DynamicBundle 진입점
├── actions/                         # 우클릭 / 단축키 액션
├── model/                           # 순수 DTO (TableInfo / RoutineInfo / PackageInfo / SessionInfo / LockInfo)
├── repository/                      # 데이터 출처 추상화
│   ├── Das*Repository.kt            # IntelliJ DAS 캐시 (즉시, 오프라인)
│   └── Jdbc*Repository.kt           # JDBC 폴백 (ALL_* / V$* 직접 조회)
├── service/OracleDictionaryService  # DTO → UI 모델 / DDL / SQL 생성
└── ui/                              # 다이얼로그 + Tool Window
src/main/resources/
├── META-INF/plugin.xml              # <resource-bundle>로 action.<id>.text 자동 매핑
└── messages/OracleInspectorBundle{,_ko}.properties
```

## 정책

### 다국어 (i18n)
- **사용자 대면 텍스트**(라벨/툴팁/대화창/알림/메뉴/Task title)는 전부 `OracleInspectorBundle.message("key", args...)` 경유.
- 기본은 영문(Marketplace 노출용), `_ko`는 IDE locale ko_KR에서 자동 선택.
- 신규 UI 텍스트 추가 시 반드시 두 properties 양쪽에 키 추가.
- 로그 메시지(`LOG.warn/info/debug`)와 코드 주석은 i18n 대상 아님 — 작성 시점 언어 그대로.

### 커밋 / PR 언어
- **영문 conventional commit** 유지 (`feat:` / `fix:` / `docs:` / `refactor:` / `chore:`).
- 본문은 영문, 첫 줄 70자 이하.
- Co-Authored-By 트레일러 유지.

### 워크플로우
- 작업 단위는 항상 **`main`에서 새 브랜치를 따서** 시작 (`git checkout -b claude/<topic> origin/main`).
- 작업 끝나면 `./scripts/ship.sh`로 push → PR → squash merge → 원격 브랜치 삭제까지 한 방.
- 머지 후 메인 워크트리(`/Users/wooju/Projects/intellij_plugin`)에서 `git pull --ff-only` 해서 동기화.
- 같은 브랜치에 PR 머지 후 추가 커밋을 쌓지 말 것 (이전에 두 번 갈라져 새 PR을 또 만든 적 있음).

### 데이터 소스 접근
- DataGrip의 DAS 모델을 1순위로 사용 (캐시·오프라인 동작).
- 캐시가 비거나 불완전하면 자동으로 JDBC 폴백 (`com.intellij.database.dataSource.DatabaseConnectionManager.createBlocking()`).
- JDBC 쿼리는 모두 바인드 파라미터(`?`) 사용, 식별자는 `"…"` 감싸기.
- Oracle 벤더 객체(`oracle.sql.TIMESTAMPTZ` 등)는 IntelliJ 원격 ResultSet에서 직렬화 실패 — `ResultSetMetaData.getColumnType()`으로 분기해 `getString()` 폴백 사용 (참고: `JdbcTableDataRepository.readValue`).

## 알려진 함정

1. **`$` 이스케이프** — `V$SESSION` 같은 Oracle 동적 뷰는 Kotlin 문자열 안에서 `V\$SESSION` 또는 `V${'$'}SESSION`으로 적어야 함. interpolation 충돌.
2. **워크트리 안에서 main 체크아웃 불가** — main은 메인 워크트리에 잠겨 있음. 새 브랜치를 origin/main 기반으로 따는 방식 사용.
3. **`gh` 인증** — 새 머신마다 `gh auth login --git-protocol https --web` 한 번 필요. 자세한 셋업은 `README.md`의 "Setup on a new Mac" 섹션 참조.
4. **`untilBuild` null** — Marketplace 등록 시 경고 가능. 출시 라운드에 검토 필요.

## 백로그 (현재 우선순위 순)

1. Sessions Tool Window 후속 — Long Ops / Wait History / Session Stats / Explain Plan
2. Triggers 탭 실제 데이터 조회 (`ALL_TRIGGERS` JDBC)
3. SEQUENCE / SYNONYM 전용 다이얼로그
4. 인라인 검색 (Ctrl+F) — 모든 테이블/Source
5. JetBrains Marketplace 출시 준비 (LICENSE, 아이콘, vendor email, screenshots, verifyPlugin)

다른 DB(PostgreSQL/MySQL) 지원은 **보류**. Oracle 특화 도구로 포지셔닝 유지.
