# Oracle Dictionary Inspector

DataGrip / IntelliJ IDEA Ultimate에서 Oracle 테이블의 딕셔너리 정보를 탭 형태로 조회하는 플러그인입니다.  
Toad for Oracle의 "Alter Table" 화면과 유사한 UX를 제공합니다.

---

## 기능

### 진입점

| 방법 | 설명 | 단축키 |
|------|------|--------|
| DB 패널 → 테이블 우클릭 | Database 탐색기에서 테이블 선택 후 우클릭 → **Oracle Dictionary Info** | `Alt+Shift+O` |
| SQL 에디터 → 테이블명 선택 후 우클릭 | 에디터/콘솔에서 테이블명 드래그 후 우클릭 → **Oracle Dictionary Info** | `Alt+Shift+O` |

> Mac에서는 `Alt` = `⌥ Option` 키입니다.

### 탭 구성

| 탭 | 내용 |
|----|------|
| **Columns** | 컬럼 목록 — 번호, 이름, 코멘트, PK 순번, Index 포함 여부, 타입, Size, Precision, Scale, Nullable, Default |
| **Keys** | PK / UK 제약 조건 |
| **Foreign Keys** | FK 및 참조 테이블 정보 |
| **Indexes** | 인덱스명, 유니크 여부, 포함 컬럼 |
| **Checks** | CHECK 제약 조건 |
| **DDL** | `CREATE TABLE` 스크립트 + `COMMENT ON` |
| **SELECT** | 전체 컬럼 `SELECT` 쿼리 |
| **Comments SQL** | `ALL_COL_COMMENTS` 조회 쿼리 *(Phase 2 — 현재는 쿼리 텍스트만 제공)* |
| **Triggers SQL** | `ALL_TRIGGERS` 조회 쿼리 *(Phase 2 — 현재는 쿼리 텍스트만 제공)* |

### UI 특징

- **상단 바**: 테이블 코멘트(이탤릭) + 새로고침 버튼 (현재 탭 유지)
- **Columns**: PK 순번 표시, Index 포함 컬럼 `●` 표시
- **정렬**: 오름차순 → 내림차순 → 해제 (3단계 사이클, `#` 컬럼은 숫자 정렬)
- **SQL 탭**: 원클릭 클립보드 복사 버튼 (체크 아이콘으로 1.2초 피드백)
- **컬럼 헤더 드래그**로 순서 변경 가능
- 다크/라이트 테마 자동 대응 줄무늬 테이블

---

## 개발 환경

| 항목 | 내용 |
|------|------|
| OS | macOS (Apple Silicon, arm64) |
| Kotlin | 2.2.0 |
| Gradle | 8.10 |
| IntelliJ Platform Plugin | `org.jetbrains.intellij.platform` 2.6.0 |
| 빌드 JDK | Temurin 21 (`~/Library/Java/JavaVirtualMachines/jdk-21.0.5+11`) |
| 대상 IDE | IntelliJ IDEA Ultimate 2024.3.5+ |

> **빌드 JDK를 별도로 지정하는 이유**  
> Kotlin 컴파일러 내부 `JavaVersion.parse()`가 JBR 25 버전 문자열을 파싱하지 못해 빌드 실패합니다.  
> `gradle.properties`의 `org.gradle.java.home`으로 Temurin 21 경로를 직접 지정하세요.

---

## 빌드 & 실행

```bash
# 샌드박스 IDE 실행 (플러그인 자동 로드)
./gradlew runIde

# 배포용 ZIP 빌드 → build/distributions/*.zip
./gradlew buildPlugin
```

빌드된 ZIP은 DataGrip / IntelliJ IDEA Ultimate의  
**Settings → Plugins → ⚙️ → Install Plugin from Disk...** 에서 설치할 수 있습니다.

---

## 프로젝트 구조

```
src/main/
├── kotlin/com/github/wooju/oracleinspector/
│   ├── actions/
│   │   ├── ShowOracleTableInfoAction.kt             # DB 패널 우클릭 액션
│   │   └── ShowOracleTableInfoFromEditorAction.kt   # SQL 에디터 우클릭 액션
│   ├── service/
│   │   └── OracleDictionaryService.kt               # 데이터 추출 + SQL/DDL 생성
│   └── ui/
│       ├── DictionaryTableModel.kt                  # 범용 TableModel (숫자 정렬 지원)
│       └── OracleTableInfoDialog.kt                 # 탭 다이얼로그
└── resources/META-INF/plugin.xml
```

---

## 로드맵 (Phase 2)

- [ ] JDBC 연동으로 Comments / Triggers 탭 실제 데이터 조회
- [ ] 컬럼 너비 사용자 조정값 저장
- [ ] 인라인 검색 (Ctrl+F)
- [ ] JetBrains 플러그인 마켓플레이스 배포
