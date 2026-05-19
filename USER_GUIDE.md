# Oracle Dictionary Inspector — 사용자 매뉴얼

DataGrip / IntelliJ IDEA Ultimate에서 Oracle 객체(테이블·뷰·프로시저·펑션·패키지·시퀀스·시노님·세션·락)를 한 화면에서 조회하는 Toad 스타일 도구. 이 문서는 **무엇을 어떻게 쓰는가**를 사용자 관점에서 다룹니다.

> 빌드/개발 환경은 [README.md](README.md), Claude Code 세션 컨텍스트는 [CLAUDE.md](CLAUDE.md)를 보세요.

---

## 1. 설치

1. `./gradlew buildPlugin` 으로 `build/distributions/Oracle Dictionary Inspector-*.zip` 생성
2. DataGrip / IntelliJ IDEA Ultimate 의 **Settings → Plugins → ⚙ → Install Plugin from Disk…** 로 ZIP 선택
3. IDE 재시작 → 하단 Tool Window 영역에 **Oracle Sessions** 아이콘이 추가되어 있으면 성공

권한 요구사항(읽기 전용):

| 기능 | 필요 권한 | 비고 |
|------|----------|------|
| 테이블 / 뷰 / 프로시저 / 펑션 / 패키지 정보 | 보통의 사용자 권한 (`ALL_*` 뷰 SELECT) | DataGrip의 기존 사용자 권한이면 충분 |
| Sessions / Locks / Long Ops / Wait History / Stats / Explain Plan | `V$` 동적 뷰 SELECT | DBA에게 `GRANT SELECT_CATALOG_ROLE TO <user>;` 1회 요청이 가장 간단 |
| Kill Session | `ALTER SYSTEM` | 운영 환경에서는 부여 신중 |

권한이 부족할 때 (`ORA-00942` / `ORA-01031`)는 우상단 알림(BALLOON)으로 필요한 `GRANT` 문 전체를 안내합니다.

---

## 2. 진입점 (객체 조회)

세 가지 방법, **모두 단축키 `Alt+Shift+O`** (macOS: `⌥+Shift+O`).

### 2-1. Database 패널 트리에서 우클릭
스키마 트리에서 객체 노드 우클릭 → **Oracle Dictionary Info**.
인식하는 객체: `TABLE / VIEW / 표준 PROCEDURE / FUNCTION / PACKAGE / SEQUENCE / SYNONYM`. 패키지 내부 프로시저는 메뉴에서 비활성.

### 2-2. SQL 에디터에서 식별자 선택 또는 캐럿 위치
SQL 콘솔/에디터에서:
- 식별자를 **드래그 선택** 후 `Alt+Shift+O`, 또는
- 식별자 **단어 위에 캐럿만** 두고 `Alt+Shift+O`

다음 형식 모두 지원:
- `EMP` — 검색 우선순위: ① SCHEMA.NAME처럼 owner가 명시되면 그 owner ② 현재 다이얼로그 컨텍스트의 OWNER ③ 데이터소스의 모든 스키마
- `HR.EMP` — OWNER 명시
- `MY_PKG$2` — Oracle 식별자 문자(`_`, `#`, `$`) 포함

같은 이름의 객체가 여러 스키마에 있으면 선택 다이얼로그가 뜹니다 (현재 OWNER가 있으면 그 후보가 맨 위).

### 2-3. 다이얼로그 안의 Source 에디터에서 같은 단축키
프로시저/패키지 다이얼로그의 Source 탭에서 식별자 위에 캐럿 두고 `Alt+Shift+O` — 그 식별자가 가리키는 다른 객체의 다이얼로그가 또 열림. 비모달이라 **여러 객체 동시 비교 가능**.

---

## 3. 테이블 / 뷰 다이얼로그

테이블과 뷰는 같은 다이얼로그를 사용합니다. 차이는 일부 탭 동작과 **DDL** 탭 내용뿐:

| 탭 | 테이블 | 뷰 | 내용 |
|----|--------|----|----|
| **Columns** | ✓ | ✓ | 번호 / 이름 / 코멘트 / PK 순번 / Index 포함 / 타입 / Size / Precision / Scale / Nullable / Default |
| **Keys** | ✓ | (보통 비어있음) | PK · UK 제약 |
| **Foreign Keys** | ✓ | (없음) | FK + 참조 테이블 |
| **Indexes** | ✓ | (없음) | 인덱스명 · 유니크 · 포함 컬럼 |
| **Checks** | ✓ | (없음) | CHECK 제약 |
| **Triggers** | ✓ | ✓ (INSTEAD OF) | 이름 / 유형 / 이벤트 / 상태 / ACTION_TYPE (`ALL_TRIGGERS`) |
| **Data** | ✓ | ✓ | **실제 데이터 미리보기 — 500행 페이징 + WHERE / ORDER BY 필터** |
| **DDL** | `CREATE TABLE …` | `CREATE OR REPLACE VIEW … AS <text>` (`ALL_VIEWS.TEXT`) | `COMMENT ON` 포함 |
| **SELECT** | ✓ | ✓ | 전체 컬럼 `SELECT` 쿼리 (복사용) |
| **Comments SQL** | ✓ | ✓ | `ALL_COL_COMMENTS` 조회 쿼리 텍스트 |

### Data 탭 사용법

```
[← →] 페이지 N · 행 X-Y (더 있음)                              [↻]
WHERE [_______________]   ORDER BY [_______________]
┌──────────────────────────────────────────────────────────┐
│ 데이터 그리드                                              │
│  ...                                                      │
└──────────────────────────────────────────────────────────┘
```

- **페이지당 500행** — `OFFSET … FETCH NEXT … ROWS ONLY`
- `WHERE` / `ORDER BY` 입력 후 **Enter** 누르면 즉시 페이지 1부터 다시 조회
- 둘 다 비우고 Enter → 필터 해제
- `(더 있음)` 라벨이 보이면 다음 페이지 있음 — `→` 버튼 활성
- `TIMESTAMP WITH TIME ZONE` 같은 Oracle 벤더 타입도 문자열로 안전하게 표시 (`<failed to load>` 없음)
- `BLOB`/`BINARY` 컬럼은 `<BINARY N bytes>` placeholder

### 새로고침
상단 바 오른쪽 `↻` 버튼 — DAS 캐시가 아닌 **JDBC로 ALL_* 뷰를 다시 조회**합니다. 컬럼 추가/삭제/주석 변경 같은 DDL 후 즉시 반영하고 싶을 때.

---

## 4. 프로시저 / 펑션 다이얼로그

| 탭 | 내용 |
|----|------|
| **Source** | `ALL_SOURCE` 본문 — IntelliJ Editor (라인 번호, SQL 신택스, 캐럿 행 강조, 컴파일 오류 라인 강조) |
| **Execute** | 매개변수가 채워진 `BEGIN…END;` 블록 또는 `SELECT fn(...) FROM DUAL` — SQL 콘솔에 복사해 IN 자리에 값 채우고 실행 가능 |
| **Errors** | `ALL_ERRORS` 컴파일 오류 (line / position / text). **행 더블클릭 → Source 탭으로 점프 + 해당 라인 캐럿 이동** |
| **Arguments** | 매개변수 위치 / 이름 / IN·OUT 방향 / 데이터 타입 / 기본값 |

### Source 에디터 — 에러 라인 강조

Errors 탭에 오류가 있으면 Source 에디터의 해당 라인이 **빨간 배경**으로 칠해지고, 우측 에러 스트라이프에 마우스를 올리면 `Line N, col M: 메시지` 툴팁이 뜹니다.

---

## 5. 패키지 다이얼로그

| 탭 | 내용 |
|----|------|
| **Spec** | `ALL_SOURCE TYPE='PACKAGE'` — Source 탭과 동일 에디터, 해당 영역의 컴파일 오류만 빨간 강조 |
| **Body** | `ALL_SOURCE TYPE='PACKAGE BODY'` — 없으면 안내 텍스트. Body 영역 오류만 별도 강조 |
| **Routines** | `ALL_PROCEDURES` — 패키지 내부 PROCEDURE/FUNCTION 목록 (이름 / 오버로드 / 종류) |
| **Errors** | `ALL_ERRORS TYPE IN ('PACKAGE', 'PACKAGE BODY')` — 행 더블클릭 시 sourceType에 맞춰 Spec 또는 Body 탭으로 점프 |

---

## 6. SEQUENCE / SYNONYM 다이얼로그

각각 작은 Property/Value 테이블 한 개로 핵심 정보만 표시.

| Sequence 표시 항목 | Synonym 표시 항목 |
|-------------------|-------------------|
| Owner / Name / Min Value / Max Value / Increment By / Cycle / Order / Cache Size / Last Number | Owner / Name / References (`OWNER.NAME`) / DB Link |

원본 객체로 점프하려면 References 값을 복사 후 `Alt+Shift+O`.

---

## 7. Oracle Sessions Tool Window

IDE 하단 영역의 **Oracle Sessions** 아이콘 — 첫 클릭 시 자동으로 첫 번째 Oracle 데이터소스로 로드.

### 상단 공통 툴바
```
[DataSource ▼] [↻] [☐ Auto 5s] [☐ Background]     "세션 N건 · 14:23:05"
```
- **DataSource** 콤보: 프로젝트의 Oracle 데이터소스만 노출
- **↻** 활성 탭만 새로고침
- **Auto 5s**: 5초마다 활성 탭 자동 새로고침
- **Background**: Sessions 탭에서 `TYPE='BACKGROUND'` 세션 포함 여부

### Sessions 탭 (상단)

| 컬럼 | 의미 |
|------|------|
| SID / SERIAL# | 세션 식별자 (Kill 명령에서 쓰임) |
| USER | 로그인 사용자 |
| STATUS | `ACTIVE` (초록 텍스트) / `INACTIVE` / `KILLED` |
| WAIT | wait class |
| EVENT | 현재 대기 이벤트 |
| LAST CALL | 마지막 호출 이후 초 |
| MACHINE / PROGRAM / MODULE | 클라이언트 정보 |
| SQL_ID | 현재 실행 중 SQL |
| BLOCKED BY | 차단 중인 SID (있으면 행 배경 **빨강**) |

#### 필터 행
```
USER [_____] STATUS [_____] PROGRAM [_____] MODULE [_____] [Clear]
```
입력 즉시 **클라이언트 사이드 필터** (`TableRowSorter`) — 재조회 없음. 모든 필드 case-insensitive contains, 동시 입력 시 AND 조건. Clear로 일괄 비움.

#### 우클릭 메뉴
**Kill Session SID,SERIAL# (USER)** — 클릭 시 확인 다이얼로그에 실행될 SQL까지 그대로 표시. `KILL` 버튼으로 진행, `취소` 가능.

### Sessions 탭 하단 — 선택 세션 상세 (sub-tab 4개)

세션 행을 클릭하면 **하단 sub-tab 4개가 동시에 자동 갱신**:

#### Current SQL
선택 세션의 `V$SQLAREA.SQL_FULLTEXT`. Source 탭과 같은 IntelliJ Editor라 라인 번호/SQL 신택스 적용. SQL_ID 없거나 캐시에서 빠진 경우 안내 메시지.

#### Wait History
`V$SESSION_WAIT_HISTORY` 최근 10건 — SEQ# / EVENT / WAIT_TIME (centiseconds) / P1·P2·P3.

#### Session Stats
`V$SESSTAT + V$STATNAME` 중 자주 보는 통계 (logical reads / physical reads / parse count (hard/total) / execute count / redo size / CPU / sorts (memory/disk) / commits / rollbacks / PGA·UGA memory / SQL*Net bytes). 큰 값은 천 단위 콤마.

#### Explain Plan
`V$SQL_PLAN` (CHILD_NUMBER=0) — 들여쓰기 테이블 형태로 실행 계획 노드. 컬럼: ID / Operation (depth만큼 들여쓰기) / Object / Rows / Bytes / Cost / CPU / Time.
- **빨강 배경**: `TABLE ACCESS FULL`, `MERGE JOIN CARTESIAN`
- **초록 글자**: `INDEX *` (효율적인 인덱스 스캔)

### Locks 탭

`V$LOCKED_OBJECT + V$SESSION + ALL_OBJECTS` 조인. 한 행 = 한 잠금:

| 컬럼 | 의미 |
|------|------|
| SID / SERIAL# / USER | 잠금 보유 세션 |
| OBJECT | 잠긴 객체 (`OWNER.NAME`) |
| OBJ TYPE | TABLE / VIEW 등 |
| MODE | `None` / `Row-S` / `Row-X` / `Share` / `S/Row-X` / `Exclusive` (숫자→텍스트 변환) |
| SECS WAIT | 대기 시간 |
| BLOCKED BY | 이 세션을 차단하는 다른 SID |

`Exclusive`, `Row-X` 모드는 행 배경 **빨강 강조**.

#### 우클릭 메뉴
**Kill Holder Session** — Sessions 탭의 Kill과 같은 흐름 (확인 다이얼로그 + 실행 SQL 표시).

### Long Ops 탭

`V$SESSION_LONGOPS` 중 진행 중인 작업(`SOFAR < TOTALWORK`)만:

| 컬럼 | 의미 |
|------|------|
| SID / SERIAL# / USER | 작업 수행 세션 |
| OPERATION / TARGET | 무엇을 하는 중인지 |
| **PROGRESS** | `0..100%` (`SOFAR/TOTALWORK`) — 진행률 강조 |
| SOFAR / TOTAL | 절대값 (블록 수 등) |
| UNITS | 단위 |
| ELAPSED / REMAINING | 초 |
| MESSAGE | Oracle이 직접 적은 진행 메시지 |

대용량 백업·인덱스 빌드·통계 수집 등이 여기 보임.

---

## 8. 인라인 검색

**모든 테이블에 자동 적용** — 테이블에 포커스가 있을 때 글자를 타이핑하면 매칭 행으로 점프 (IntelliJ 관례). 단축키 없이 그냥 타이핑만 시작하면 됩니다.

예: Columns 탭에서 `CREATED_` 타이핑 → `CREATED_AT` 행으로 자동 이동.

> Source 탭은 IntelliJ Editor이므로 IDE 기본 `Cmd/Ctrl+F` (Find Bar)도 작동.

---

## 9. 컬럼 너비 저장

사용자가 컬럼 너비를 조정하면 **다이얼로그를 다시 열어도 그대로 유지**됩니다. `PropertiesComponent`에 영속 — IDE 재시작 후에도 유지.

저장 키 네임스페이스: `OracleInspector.colWidth.<tableId>.<columnHeader>` — 다른 사용자 설정과 충돌하지 않음.

> 현재 적용 범위: 테이블 다이얼로그 모든 탭 + Sessions 탭의 세션 테이블. 다른 테이블은 추후 확장.

---

## 10. 다국어

- **기본 = 영문** (JetBrains Marketplace 페이지가 default를 표시)
- **한국어** (`_ko`) — IDE 로케일이 `ko_KR`일 때 자동 선택, 누락 키는 영문으로 자동 폴백
- 신규 메시지가 추가되면 두 properties (`messages/OracleInspectorBundle{,_ko}.properties`) 모두에 채워야 함

IDE 로케일 강제 변경: **Help → Edit Custom VM Options…** 에 `-Duser.language=ko` 추가 후 재시작.

---

## 11. 단축키 요약

| 단축키 | 어디서 | 동작 |
|--------|--------|------|
| `Alt+Shift+O` | DB 트리 노드 선택 | 해당 객체의 Dictionary Info 다이얼로그 |
| `Alt+Shift+O` (macOS: `⌥+Shift+O`) | SQL 에디터 / Source 에디터 | 캐럿 위치 식별자(또는 선택 텍스트)로 다이얼로그 |
| `Enter` | Data 탭 WHERE / ORDER BY | 필터 적용 + 페이지 1로 리셋 |
| `Cmd/Ctrl+F` | Source 에디터 | IDE 기본 Find Bar (Source 탭은 IntelliJ Editor) |
| `(타이핑)` | 모든 JBTable | 인라인 검색 (포커스가 테이블에 있어야 함) |
| `Double-click` | Errors 탭 행 | Source 탭으로 점프 + 해당 라인 캐럿 이동 |

---

## 12. 트러블슈팅

### "DB 조회 실패: ORA-00942"
`V$` 또는 `ALL_*` 뷰 SELECT 권한 없음. 알림에 표시되는 `GRANT` 문 그대로 DBA에게 요청 — 가장 간단한 방법은:
```sql
GRANT SELECT_CATALOG_ROLE TO <사용자>;
```

### "KILL 실패: ORA-01031"
`ALTER SYSTEM` 권한 없음. 운영자 권한이 필요한 작업이라 일반 개발자 계정엔 거의 부여되지 않습니다.

### "`<failed to load> oracle.sql.TIMESTAMPTZ`" 같은 표시
Data 탭에서 발생하는 알려진 IntelliJ 원격 직렬화 문제. 이미 우회 처리되어 있어 정상 출력되지만, 만약 다시 보이면 [JdbcTableDataRepository.readValue](src/main/kotlin/com/github/wooju/oracleinspector/repository/JdbcTableDataRepository.kt)의 SQL 타입 분기에 누락된 타입이 있는 것 — 이슈 등록 환영.

### 다이얼로그 열었는데 컬럼이 거의 비어있음
DataGrip이 해당 객체를 한 번도 introspection하지 않아 DAS 캐시가 비어있음. 다이얼로그가 자동으로 JDBC 폴백을 시작하므로 잠깐 기다리면 채워짐. 안 채워지면 우상단 `↻` 새로고침.

### 다이얼로그 안의 Source에서 PL/SQL 신택스가 안 보임
드물게 일부 IntelliJ Database 플러그인 버전에서 발생. `gradle.properties`의 IntelliJ 버전을 변경하면 영향 받을 수 있음. PL/SQL 전용 dialect 지정 없이 표준 SQL 신택스만 적용되므로, 핵심 키워드는 인식되되 일부 PL/SQL 전용 키워드는 plain text로 보일 수 있음.

---

## 13. 권장 워크플로우

### 일상: SQL 디버깅
1. 콘솔에서 느린 쿼리 실행
2. **Oracle Sessions** Tool Window 열기 → 자동 새로고침 켜기
3. STATUS=`ACTIVE`, USER=내 계정 필터 → 내 세션 선택
4. 하단 sub-tab으로 한 화면에서: 무슨 SQL 돌리고 있는지 → 어떤 wait → 자원 사용량 → 실행 계획

### 일상: 코드 리뷰
1. SQL 에디터에서 익숙치 않은 테이블/뷰 발견 → 식별자 위 `Alt+Shift+O`
2. Columns 탭으로 스키마 파악, Data 탭으로 실제 모양 확인
3. 같은 식별자가 다른 객체일 수도 → 선택 다이얼로그에서 분기

### 일상: 배포 후 모니터링
1. **Oracle Sessions** → **Long Ops** 탭으로 큰 작업(인덱스 빌드 등) 진행률 확인
2. **Locks** 탭으로 차단 상황 점검 → 필요 시 Kill Holder

### 운영: 장애 대응
1. **Sessions** 탭에서 BLOCKED BY 빨강 행 식별
2. 차단 SID로 BLOCKED BY 행 follow → 원인 세션 찾기
3. Stats / Wait History로 패턴 분석
4. 필요하면 Kill Session (확인 다이얼로그 두 단계 거침)
