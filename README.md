# Oracle Dictionary Inspector

DataGrip / IntelliJ IDEA Ultimate에서 Oracle **테이블·뷰·프로시저·펑션**의 딕셔너리 정보를 탭 형태로 빠르게 조회하는 플러그인입니다.
Toad for Oracle의 "Describe / Alter Table" 화면과 유사한 UX를 목표로 합니다.

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
| **TABLE / VIEW** | 테이블 정보 다이얼로그 (아래) |
| **PROCEDURE / FUNCTION** (standalone) | 프로시저·펑션 다이얼로그 (아래) |
| **PACKAGE / SEQUENCE / SYNONYM** | 가벼운 `JBPopup`으로 메타 정보 표시 |

---

### 테이블 정보 다이얼로그

| 탭 | 내용 |
|----|------|
| **Columns** | 번호, 이름, 코멘트, PK 순번, Index 포함 여부, 타입, Size, Precision, Scale, Nullable, Default |
| **Keys** | PK / UK 제약 조건 |
| **Foreign Keys** | FK 및 참조 테이블 정보 |
| **Indexes** | 인덱스명, 유니크 여부, 포함 컬럼 |
| **Checks** | CHECK 제약 조건 |
| **Data** | 실제 데이터 미리보기 — **500행 페이징** + `WHERE` / `ORDER BY` 필터 (Enter로 적용, 페이지 1로 리셋) |
| **DDL** | `CREATE TABLE` 스크립트 + `COMMENT ON` |
| **SELECT** | 전체 컬럼 `SELECT` 쿼리 |
| **Comments SQL** | `ALL_COL_COMMENTS` 조회 쿼리 |
| **Triggers SQL** | `ALL_TRIGGERS` 조회 쿼리 |

#### Data 탭 동작 상세
- `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` (Oracle 12c+) 기반 페이징
- `COUNT(*)` 없이 `pageSize + 1` 행을 요청해 `hasMore`만 판정 (큰 테이블에서 비싼 카운트 회피)
- `TIMESTAMP WITH TIME ZONE` / CLOB / NCLOB / SQLXML / ROWID 등 벤더 객체는 `ResultSetMetaData` SQL 타입 기반으로 `String` 변환 (`<failed to load> oracle.sql.TIMESTAMPTZ` 회피)
- BLOB / 바이너리 타입은 `<BINARY N bytes>` placeholder

---

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

## 빌드 & 실행

```bash
# 샌드박스 IDE 실행 (플러그인 자동 로드)
./gradlew runIde

# 배포용 ZIP 빌드 → build/distributions/*.zip
./gradlew buildPlugin
```

빌드된 ZIP은 DataGrip / IntelliJ IDEA Ultimate의
**Settings → Plugins → ⚙️ → Install Plugin from Disk…** 로 설치할 수 있습니다.

---

## 프로젝트 구조

```
src/main/
├── kotlin/com/github/wooju/oracleinspector/
│   ├── actions/
│   │   ├── OracleInspectorDataKeys.kt              # 다이얼로그 컨텍스트(OWNER/DataSource) DataKey
│   │   ├── ShowOracleTableInfoAction.kt            # DB 패널 우클릭 액션
│   │   └── ShowOracleTableInfoFromEditorAction.kt  # 에디터/Source 탭 단축키 액션
│   ├── model/
│   │   ├── RoutineInfo.kt                          # 프로시저·펑션 DTO
│   │   └── TableInfo.kt                            # 테이블 DTO
│   ├── repository/
│   │   ├── DasRoutineRepository.kt                 # 캐시(DAS) → RoutineInfo
│   │   ├── DasTableRepository.kt                   # 캐시(DAS) → TableInfo
│   │   ├── JdbcRoutineRepository.kt                # JDBC → RoutineInfo
│   │   ├── JdbcTableDataRepository.kt              # JDBC → Data 탭 페이징 조회
│   │   ├── JdbcTableMetadataRepository.kt          # JDBC → TableInfo
│   │   ├── RoutineMetadataRepository.kt            # Routine repo 인터페이스
│   │   └── TableMetadataRepository.kt              # Table repo 인터페이스
│   ├── service/
│   │   └── OracleDictionaryService.kt              # DDL/SELECT/Execute 등 생성 + ModelBuilder
│   └── ui/
│       ├── DictionaryTableModel.kt                 # 범용 TableModel (숫자 정렬 지원)
│       ├── OracleRoutineInfoDialog.kt              # 프로시저·펑션 다이얼로그
│       └── OracleTableInfoDialog.kt                # 테이블 다이얼로그
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
- [ ] Triggers 탭 실제 데이터 조회
- [ ] PACKAGE / SEQUENCE / SYNONYM 전용 다이얼로그
- [ ] 인라인 검색 (Ctrl+F) — 모든 테이블/Source에서
- [ ] 컬럼 너비 사용자 조정값 저장
- [ ] JetBrains 플러그인 마켓플레이스 배포

---

## 라이선스

(미정 — 출시 전 결정 필요)
