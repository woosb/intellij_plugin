# Oracle Dictionary Inspector — 개발 노트

## 1. 프로젝트 개요

DataGrip / IntelliJ IDEA Ultimate에서 Oracle 테이블을 우클릭하면  
Oracle 딕셔너리 정보를 Toad for Oracle의 "Alter Table" 화면처럼 탭 형태로 보여주는 플러그인.

---

## 2. 개발 환경

| 항목 | 내용 |
|------|------|
| OS | macOS (Apple Silicon, arm64) |
| Gradle wrapper | 8.10 |
| Kotlin | 2.2.0 |
| IntelliJ Platform Plugin | `org.jetbrains.intellij.platform` 2.6.0 |
| 빌드용 JDK | Temurin 21.0.5 (`~/Library/Java/JavaVirtualMachines/jdk-21.0.5+11`) |
| 런타임 JDK | JBR 25.0.2 (IntelliJ 번들) |
| 대상 IDE | IntelliJ IDEA Ultimate 2024.3.5 + `bundledPlugin("com.intellij.database")` |

### 왜 JDK 21을 따로 설치했나?
Kotlin 컴파일러 내부 `JavaVersion.parse()`가 Java 25 버전 문자열을 파싱하지 못해 빌드 실패.  
`gradle.properties`에 `org.gradle.java.home` 으로 Temurin 21 경로 직접 지정.

### 실행 명령
```bash
./gradlew runIde      # 샌드박스 IDE 실행 (플러그인 자동 로드)
./gradlew buildPlugin # build/distributions/*.zip 생성 → DataGrip에 직접 설치 가능
```

---

## 3. 프로젝트 구조

```
intellij_plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradle/wrapper/
└── src/main/
    ├── kotlin/com/github/wooju/oracleinspector/
    │   ├── actions/
    │   │   ├── ShowOracleTableInfoAction.kt            # DB 패널 우클릭 액션
    │   │   └── ShowOracleTableInfoFromEditorAction.kt  # SQL 에디터 우클릭 액션
    │   ├── service/
    │   │   └── OracleDictionaryService.kt              # 데이터 추출 + SQL/DDL 생성
    │   └── ui/
    │       ├── DictionaryTableModel.kt                 # 범용 TableModel
    │       └── OracleTableInfoDialog.kt                # 탭 다이얼로그
    └── resources/META-INF/plugin.xml
```

---

## 4. 액션 진입점

| 진입점 | 등록 그룹 | 단축키 |
|--------|-----------|--------|
| DB 패널 → 테이블 우클릭 | `DatabaseViewPopupMenu` | `Alt+Shift+O` (Mac: `⌥⇧O`) |
| SQL 에디터 → 테이블명 드래그 후 우클릭 | `EditorPopupMenu` | `Alt+Shift+O` (Mac: `⌥⇧O`) |

---

## 5. 플러그인 동작 흐름

```
[DB 패널] 테이블 우클릭 → Oracle Dictionary Info
    └─> ShowOracleTableInfoAction
            └─> DATABASE_ELEMENTS 데이터키로 BasicElement 획득
            └─> DasTable → DbPsiFacade.findElement() → DbTable
            └─> Dbms.ORACLE 여부 확인 (update)
            └─> OracleTableInfoDialog.show()

[SQL 에디터] 테이블명 선택 → 우클릭 → Oracle Dictionary Info
    └─> ShowOracleTableInfoFromEditorAction
            └─> 선택 텍스트 추출
            └─> DATABASE_RELATED_SINGLE_DATA_SOURCE로 연결된 데이터소스 확보
            └─> 스키마 하위 탐색으로 테이블 검색
            └─> 복수 결과 시 선택 팝업
            └─> OracleTableInfoDialog.show()
```

---

## 6. 탭 구성

| 탭 | 데이터 출처 | 내용 |
|----|------------|------|
| Columns | DAS 캐시 | #, 컬럼명, 코멘트, PK, Index, 타입, Size, Precision, Scale, Nullable, Default |
| Keys | DAS 캐시 | PK / UK |
| Foreign Keys | DAS 캐시 | FK + 참조 테이블 |
| Indexes | DAS 캐시 | 인덱스명, 유니크, 포함 컬럼 |
| Checks | DAS 캐시 | CHECK 제약 |
| DDL | DAS 기반 생성 | CREATE TABLE + COMMENT ON |
| SELECT | DAS 기반 생성 | 전체 컬럼 SELECT |
| Comments SQL | SQL 텍스트 | ALL_COL_COMMENTS 쿼리 (Phase 2용) |
| Triggers SQL | SQL 텍스트 | ALL_TRIGGERS 쿼리 (Phase 2용) |

---

## 7. 핵심 API 발견사항

### DB 패널 선택 요소 가져오기
```kotlin
// DATABASE_ELEMENTS 만 동작 (DB_ELEMENTS, PSI_ELEMENT 는 null)
val elements = e.getData(DatabaseView.DATABASE_ELEMENTS)
val dasTable = elements?.getOrNull(0) as? DasTable
val dbTable  = DbPsiFacade.getInstance(project).findElement(dasTable) as? DbTable
```

### Oracle 판별
```kotlin
table.dataSource?.getDatabaseDialect()?.getDbms() == Dbms.ORACLE
```

### DAS 모델 접근
```kotlin
DasUtil.getColumns(table)                    // DasColumn
DasUtil.getIndices(table)                    // DasIndex
table.getDasChildren(ObjectKind.KEY)         // PK/UK
table.getDasChildren(ObjectKind.FOREIGN_KEY) // FK → filterIsInstance<DasForeignKey>()
table.getDasChildren(ObjectKind.CHECK)       // CHECK
```

### DataType 주의
```kotlin
col.getDataType()   // col.dataType 프로퍼티는 deprecated
// 2147483646 = DataType.MAX_SIZE → 의미없는 값, 표시 제외
fun validSize(v: Int) = v.takeIf { it > 0 && it < DataType.MAX_SIZE }
```

---

## 8. UI 구현 특이사항

- **상단 바**: 테이블 코멘트(이탤릭) + 새로고침 버튼 (현재 탭 유지하며 재로드)
- **Columns PK/Index 컬럼**: 44px 고정 + 가운데 정렬
- **숫자 정렬**: `getColumnClass()` 오버라이드 → `#` 컬럼이 숫자로 정렬
- **정렬 사이클**: 오름차순 → 내림차순 → 해제 (3번째 클릭)
- **SQL 탭 복사 버튼**: 클릭 시 체크 아이콘으로 1.2초 피드백
- **줄무늬**: `UIManager "Table.stripeColor"` (다크/라이트 테마 자동 대응)
- **컬럼 순서 변경**: `tableHeader.reorderingAllowed = true`

---

## 9. TODO (Phase 2)

- [ ] JDBC로 Comments / Triggers 탭 실제 데이터 조회
  - `DatabaseConnectionManager.create()` = suspend 함수 → 코루틴 필요
  - 반환 `RemoteConnection` ≠ `java.sql.Connection` → API 추가 조사
- [ ] 컬럼 너비 저장
- [ ] 인라인 검색 (Ctrl+F) 지원
- [ ] 실제 DataGrip 배포 (플러그인 마켓플레이스)
