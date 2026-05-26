# Oracle Dictionary Inspector

DataGrip / IntelliJ IDEA Ultimate에서 Oracle **테이블·뷰·프로시저·펑션·패키지·시퀀스·시노님·세션·락**의 딕셔너리·상태 정보를 탭 다이얼로그와 전용 Tool Window로 빠르게 조회하는 플러그인입니다.
Toad for Oracle의 "Describe / Alter Table / Session Browser" 화면을 합쳐 둔 형태의 UX를 목표로 합니다.

## 관련 문서

- 📘 **[사용자 매뉴얼 (USER_GUIDE.md)](USER_GUIDE.md)** — 설치, 모든 기능 사용법, 단축키, 트러블슈팅
- 🧪 **[테스트 시나리오 (TEST_SCENARIOS.md)](TEST_SCENARIOS.md)** — 전체 기능 회귀 테스트 절차 (P0/P1/P2 + 출시)
- 🤖 **[CLAUDE.md](CLAUDE.md)** — Claude Code 세션 컨텍스트 (정책, 빌드, 코드 구조)

---

## 기능

### 진입점

| 방법 | 설명 | 단축키 |
|------|------|--------|
| DB 패널 → 객체 우클릭 | Database 탐색기에서 테이블/프로시저 선택 후 우클릭 → **Oracle Dictionary Info** | `Alt+Shift+O` |
| SQL 에디터 → 식별자 선택 또는 캐럿 위치에서 단축키 | 식별자 위에 캐럿을 두거나 드래그 선택 후 우클릭 → **Oracle Dictionary Info** | `Alt+Shift+O` |
| 다이얼로그 Source 탭 안에서 | Source 에디터의 식별자 위에서 같은 단축키 → 그 객체의 다이얼로그/팝업 표시 | `Alt+Shift+O` |

> Mac에서는 `Alt` = `⌥ Option` 키입니다.
> `SCHEMA.NAME` 형식 입력 지원, 다이얼로그 안에서 호출 시 **현재 다이얼로그의 OWNER 우선**.

### 인식하는 객체

| 종류 | 결과 |
|------|------|
| **TABLE** | 테이블 정보 다이얼로그 (아래) |
| **VIEW** | 테이블 정보 다이얼로그 — DDL 탭은 `ALL_VIEWS.TEXT` 기반 `CREATE OR REPLACE VIEW` |
| **PROCEDURE / FUNCTION** (standalone) | 프로시저·펑션 다이얼로그 (아래) |
| **PACKAGE** | 패키지 다이얼로그 — Spec / Body / Routines / Errors |
| **SEQUENCE** | 시퀀스 다이얼로그 — MIN/MAX/INCREMENT/CYCLE/ORDER/CACHE/LAST_NUMBER (`ALL_SEQUENCES`) |
| **SYNONYM** | 시노님 다이얼로그 — 참조 객체(OWNER.NAME) + DB_LINK (`ALL_SYNONYMS`) |

---

### 테이블 정보 다이얼로그

| 탭 | 내용 |
|----|------|
| **Columns** | 번호, 이름, 코멘트, PK 순번, Index 포함 여부, 타입, Size, Precision, Scale, Nullable, Default |
| **Keys** | PK / UK 제약 조건 |
| **Foreign Keys** | FK 및 참조 테이블 정보 |
| **Indexes** | 인덱스명, 유니크 여부, 포함 컬럼 |
| **Checks** | CHECK 제약 조건 |
| **Triggers** | `ALL_TRIGGERS` 조회 결과 — 이름·유형·이벤트·상태·ACTION_TYPE |
| **Data** | 실제 데이터 미리보기 — **500행 페이징** + `WHERE` / `ORDER BY` 필터 (Enter로 적용, 페이지 1로 리셋) |
| **DDL** | `CREATE TABLE` (또는 VIEW면 `CREATE OR REPLACE VIEW`) 스크립트 + `COMMENT ON` |
| **SELECT** | 전체 컬럼 `SELECT` 쿼리 |
| **Comments SQL** | `ALL_COL_COMMENTS` 조회 쿼리 |

#### Data 탭 동작 상세
- `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (Oracle 12c+) 기반 페이징
- `COUNT(*)` 없이 `pageSize + 1` 행을 요청해 `hasMore`만 판정 (큰 테이블에서 비싼 카운트 회피)
- `TIMESTAMP WITH TIME ZONE` / CLOB / NCLOB / SQLXML / ROWID 등 벤더 객체는 `ResultSetMetaData` SQL 타입 기반으로 `String` 변환 (`<failed to load> oracle.sql.TIMESTAMPTZ` 회피)
- BLOB / 바이너리 타입은 `<BINARY N bytes>` placeholder

---

### 패키지 다이얼로그

| 탭 | 내용 |
|----|------|
| **Spec** | `ALL_SOURCE TYPE='PACKAGE'` — IntelliJ Editor (라인 번호, 신택스, **해당 영역의 컴파일 오류 라인 강조**) |
| **Body** | `ALL_SOURCE TYPE='PACKAGE BODY'` — 없으면 안내 텍스트. Body 오류만 별도로 강조 |
| **Routines** | `ALL_PROCEDURES` — 패키지 내부 PROCEDURE / FUNCTION 목록 (이름·오버로드·종류) |
| **Errors** | `ALL_ERRORS TYPE IN ('PACKAGE','PACKAGE BODY')` — 행 더블클릭 시 해당 Spec/Body 탭으로 점프 |

### 프로시저·펑션 다이얼로그

| 탭 | 내용 |
|----|------|
| **Source** | `ALL_SOURCE` 기반 PL/SQL 본문 — **IntelliJ Editor** 사용 (라인 번호, SQL 신택스 하이라이팅, 캐럿 행 강조, 폴딩) |
| **Execute** | 매개변수가 채워진 `BEGIN ... END;` 또는 `SELECT fn(...) FROM DUAL` 호출 템플릿 |
| **Errors** | `ALL_ERRORS` 컴파일 오류 목록. **행 더블클릭 → Source 탭으로 점프 + 해당 라인으로 캐럿 이동** |
| **Arguments** | 매개변수 위치 / 이름 / IN·OUT 방향 / 데이터 타입 / 기본값 |

#### Source 에디터 에러 강조
- `ALL_ERRORS`의 라인을 Source 에디터에서 **빨간 배경 + 우측 에러 스트라이프**로 표시
- 스트라이프 마우스 오버 시 `Line N, col M: 메시지` 툴팁

---

### 데이터 로딩 전략

- 1단계: IntelliJ가 캐시한 DAS(Data Source Abstraction) 모델에서 즉시 표시 — 빠르고 오프라인에서도 동작
- 2단계: 캐시가 비어있거나 불완전하면 자동으로 **JDBC**로 `ALL_*` 딕셔너리 뷰 직접 조회 (백그라운드 Task)
- 새로고침 버튼으로 언제든 JDBC 재조회 가능

---

### UI 공통

- 다크/라이트 테마 자동 대응 줄무늬 테이블
- 컬럼 정렬: 오름차순 → 내림차순 → 해제 (3단계 사이클, 숫자 컬럼은 숫자 정렬)
- 컬럼 헤더 드래그로 순서 변경
- SQL/Source 탭: 원클릭 클립보드 복사 버튼 (1.2초 체크 아이콘 피드백)
- 모든 다이얼로그는 비모달 — 여러 객체를 동시에 열어두고 비교 가능

---

## 개발 환경

| 항목 | 내용 |
|------|------|
| Kotlin | 2.2.0 |
| Gradle | 8.10 (Wrapper) |
| IntelliJ Platform Plugin | `org.jetbrains.intellij.platform` 2.6.0 |
| 빌드 JDK | Temurin 21 |
| 대상 IDE | IntelliJ IDEA Ultimate **2024.3.5** (`sinceBuild = 243`) + 번들 `com.intellij.database` |

> **빌드 JDK를 별도로 지정하는 이유**
> Kotlin 컴파일러 내부 `JavaVersion.parse()`가 JBR 25 버전 문자열을 파싱하지 못해 빌드에 실패합니다.
> `gradle.properties`의 `org.gradle.java.home`으로 Temurin 21 경로를 직접 지정하세요.

---

## 다른 맥북에서 처음 시작할 때 (Setup on a new Mac)

새 머신에서 클론한 직후 1회만 수행하면 됩니다.

```bash
# 1) 저장소 클론
git clone git@github.com:woosb/intellij_plugin.git
cd intellij_plugin

# 2) JDK 21 설치 (Temurin) — Kotlin 컴파일러가 JDK 25 버전 문자열 파싱 못 함
brew install --cask temurin@21
# 설치 경로 예시:
#   ~/Library/Java/JavaVirtualMachines/jdk-21.x.x+xx

# 3) 사용자 home의 ~/.gradle/gradle.properties 에 JDK 경로 지정 (1회만)
#    프로젝트별 gradle.properties는 본인 PC 경로를 박지 않고,
#    사용자 home 파일에 두면 어느 worktree/clone에서나 자동 적용됩니다.
mkdir -p ~/.gradle
cat >> ~/.gradle/gradle.properties <<'EOF'

# Oracle Dictionary Inspector 빌드용 (Kotlin이 JBR 25 미지원)
org.gradle.java.home=/Users/<you>/Library/Java/JavaVirtualMachines/jdk-21.x.x+xx/Contents/Home
EOF
#    → <you>와 실제 JDK 21 경로를 본인 환경에 맞게 수정

# 4) GitHub CLI 설치 + 인증 (PR/머지 자동화 스크립트 사용에 필요)
brew install gh
gh auth login --git-protocol https --web
# → GitHub.com 선택 → "Yes" → 8자리 코드 → 브라우저에서 Authorize

# 5) (선택) 사전 검증
./gradlew compileKotlin
./gradlew runIde            # 샌드박스 IDE 띄워서 동작 확인
```

> **macOS 외 OS**: 본 프로젝트는 macOS 기준으로 검증되어 있습니다. Linux/Windows에서도 JDK 21 + Gradle 8.10 wrapper로 빌드는 가능하지만, `gradle.properties`의 JDK 경로와 일부 IntelliJ 동작 (`runIde`의 IDE bundle 다운로드)이 OS별로 다를 수 있습니다.

---

## 빌드 & 실행

```bash
# 샌드박스 IDE 실행 (플러그인 자동 로드)
./gradlew runIde

# 배포용 ZIP 빌드 → build/distributions/*.zip
./gradlew buildPlugin
```

빌드된 ZIP은 DataGrip / IntelliJ IDEA Ultimate의
**Settings → Plugins → ⚙️ → Install Plugin from Disk…** 로 설치할 수 있습니다.

### Push → PR → 머지 자동화

`scripts/ship.sh`로 현재 feature 브랜치를 한 번에 `main`까지 보낼 수 있습니다.

```bash
# 사전 1회: GitHub CLI 설치 + 인증
brew install gh
gh auth login

# 작업 후: 한 번에 push + PR 생성 + squash 머지 + 원격 브랜치 삭제
./scripts/ship.sh                 # PR base = main
./scripts/ship.sh develop         # 다른 base 지정도 가능
```

스크립트는 인증·미커밋 변경·`origin/main`과의 ahead 여부를 미리 검사한 뒤
`git push -u` → `gh pr create --fill` → `gh pr merge --squash --delete-branch`
순서로 실행합니다. 머지 후 메인 워크트리에서는 `git pull --ff-only`로 동기화하세요.

---

## 프로젝트 구조

```
src/main/
├── kotlin/com/github/wooju/oracleinspector/
│   ├── OracleInspectorBundle.kt                    # i18n DynamicBundle 진입점
│   ├── actions/
│   │   ├── OracleInspectorDataKeys.kt              # 다이얼로그 컨텍스트(OWNER/DataSource) DataKey
│   │   ├── ShowOracleTableInfoAction.kt            # DB 패널 우클릭 액션
│   │   └── ShowOracleTableInfoFromEditorAction.kt  # 에디터/Source 탭 단축키 액션
│   ├── model/
│   │   ├── LockInfo.kt                             # 락 DTO
│   │   ├── PackageInfo.kt                          # 패키지 DTO
│   │   ├── RoutineInfo.kt                          # 프로시저·펑션 DTO
│   │   ├── SessionInfo.kt                          # 세션 DTO
│   │   ├── SimpleObjectInfo.kt                     # 시퀀스·시노님 DTO
│   │   └── TableInfo.kt                            # 테이블 DTO
│   ├── repository/
│   │   ├── DasPackageRepository.kt                 # 캐시(DAS) → PackageInfo
│   │   ├── DasRoutineRepository.kt                 # 캐시(DAS) → RoutineInfo
│   │   ├── DasTableRepository.kt                   # 캐시(DAS) → TableInfo
│   │   ├── JdbcPackageRepository.kt                # JDBC → PackageInfo
│   │   ├── JdbcRoutineRepository.kt                # JDBC → RoutineInfo
│   │   ├── JdbcSessionRepository.kt                # JDBC → SessionInfo / LockInfo (V$* 조회)
│   │   ├── JdbcSimpleObjectRepository.kt           # JDBC → SimpleObjectInfo (SEQUENCE/SYNONYM)
│   │   ├── JdbcTableDataRepository.kt              # JDBC → Data 탭 페이징 조회
│   │   ├── JdbcTableMetadataRepository.kt          # JDBC → TableInfo
│   │   ├── PackageMetadataRepository.kt            # Package repo 인터페이스
│   │   ├── RoutineMetadataRepository.kt            # Routine repo 인터페이스
│   │   └── TableMetadataRepository.kt              # Table repo 인터페이스
│   ├── service/
│   │   └── OracleDictionaryService.kt              # DDL/SELECT/Execute 등 생성 + ModelBuilder
│   └── ui/
│       ├── ColumnWidthMemo.kt                      # 컬럼 너비 PropertiesComponent 저장
│       ├── DictionaryTableModel.kt                 # 범용 TableModel (숫자 정렬 지원)
│       ├── OraclePackageInfoDialog.kt              # 패키지 다이얼로그
│       ├── OracleRoutineInfoDialog.kt              # 프로시저·펑션 다이얼로그
│       ├── OracleSimpleObjectDialogs.kt            # 시퀀스·시노님 다이얼로그
│       ├── OracleTableInfoDialog.kt                # 테이블·뷰 다이얼로그
│       ├── TableSearchSupport.kt                   # 인라인 검색 (TableSpeedSearch 래퍼)
│       └── sessions/
│           ├── OracleSessionsPanel.kt              # Sessions Tool Window UI
│           └── OracleSessionsToolWindowFactory.java # ToolWindowFactory (Java — Kotlin synthetic 오버라이드 회피)
└── resources/META-INF/plugin.xml
```

---

## 로드맵

- [x] 테이블 메타데이터 (Columns / Keys / FK / Indexes / Checks / DDL / SELECT)
- [x] JDBC 자동 폴백 (DAS 캐시가 불완전할 때)
- [x] 프로시저·펑션 (Source / Execute / Errors / Arguments)
- [x] Data 탭 (500행 페이징 + WHERE/ORDER BY 필터)
- [x] Source 에디터 (라인 번호 + 신택스 + 에러 라인 강조)
- [x] Describe 액션 확장 (VIEW/PACKAGE/SEQUENCE/SYNONYM + OWNER 우선)
- [x] VIEW DDL 실제 본문 조회 (`ALL_VIEWS.TEXT`)
- [x] PACKAGE 전용 다이얼로그 (Spec/Body/Routines/Errors)
- [x] 다국어 (영문 기본 + 한국어, IDE locale 자동)
- [x] Triggers 탭 실제 데이터 조회 (`ALL_TRIGGERS`)
- [x] SEQUENCE / SYNONYM 전용 다이얼로그
- [x] Sessions Tool Window — Long Ops 탭 (`V$SESSION_LONGOPS`) + Wait History sub-tab (`V$SESSION_WAIT_HISTORY`)
- [x] 컬럼 너비 사용자 조정값 저장 (`PropertiesComponent`)
- [x] Session Stats sub-tab (`V$SESSTAT + V$STATNAME`)
- [x] Explain Plan sub-tab (`V$SQL_PLAN`) — 비싼 OPERATION 빨강 강조
- [x] 모든 테이블에 인라인 검색 (`TableSpeedSearch` — 포커스 후 타이핑)
- [x] JetBrains 플러그인 마켓플레이스 배포 (v1.0.1 — moderation 수정 완료)
- [ ] Ctrl+F 인라인 검색 — Source 에디터 포함 전체 지원

---

## 라이선스

**MIT License** — 자세한 내용은 루트의 [LICENSE](LICENSE) 파일 참조.

요약:
- 누구나 무료로 사용·수정·재배포 가능 (상업적 사용 포함)
- 저작권 표기와 라이선스 텍스트를 유지하면 됨
- 무보증 — 사용으로 인한 책임은 사용자에게
