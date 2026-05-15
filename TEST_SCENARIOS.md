# Oracle Dictionary Inspector — 테스트 시나리오

> ⚠ 모든 시나리오는 IDE에서 직접 검증해야 합니다 (UI 동작은 컴파일러로 확인 불가).
> 또한 모든 JDBC 동작은 **SELECT 전용** — 어떤 시나리오에서도 DB 데이터가 변경되어서는 안 됩니다.

---

## A. Step 1 회귀 — 기존 동작 유지

| #  | 시나리오 | 기대 결과 |
|----|---------|----------|
| A1 | Database 트리에서 Oracle 테이블 우클릭 → "Oracle Dictionary Info" | 기존과 동일한 탭 구성 (Columns / Keys / Foreign Keys / Indexes / Checks / DDL / SELECT / Comments SQL / Triggers SQL) |
| A2 | SQL 에디터에서 테이블명 드래그 → `Alt+Shift+O` | 테이블 다이얼로그 오픈 (기존 동작) |
| A3 | Columns 탭의 정렬 토글 (미정렬 → 오름차 → 내림차 → 미정렬) | 기존과 동일 |
| A4 | DDL / SELECT / Comments SQL / Triggers SQL 복사 버튼 | 클립보드에 복사, 체크 아이콘 깜빡임 |

---

## B. Step 2 — JDBC 폴백 & 새로고침

| #  | 시나리오 | 기대 결과 |
|----|---------|----------|
| B1 | 캐시가 비어있는 데이터소스에서 테이블 다이얼로그 진입 (introspection 한 적 없는 데이터소스) | 즉시 빈/불완전 표시 후, 자동 백그라운드 작업("캐시 불완전 — 자동 새로고침" 상태 라벨) → 잠시 후 완전한 데이터로 갱신 |
| B2 | 정상 캐시 상태에서 다이얼로그 진입 | 즉시 캐시 데이터 표시, 자동 폴백 없음 (상태 라벨 빈 상태) |
| B3 | 새로고침 버튼 클릭 | 버튼 비활성 + 스피너 아이콘, 하단 progress bar에 "테이블 — DB에서 메타데이터 조회", 완료 후 갱신, 현재 선택 탭 유지 |
| B4 | DB에서 컬럼 추가 후 새로고침 | DDL 탭에 새 컬럼 반영 |
| B5 | DB에서 컬럼 코멘트 변경 후 새로고침 | Columns 탭의 Comment / DDL의 `COMMENT ON COLUMN` 반영 |
| B6 | 미연결 데이터소스에서 새로고침 | DataGrip이 비밀번호/연결 프롬프트 (DataGrip 표준 동작). 사용자가 취소하거나 실패하면 우상단 BALLOON 알림 "DB 조회 실패: ..." |
| B7 | 새로고침 진행 중에 버튼 다시 클릭 | 무시됨 (loading 가드) |
| B8 | 데이터소스 권한 부족(SELECT 거부)인 테이블 조회 | "DB 조회 실패: ORA-..." 알림, 기존 데이터 유지 |

---

## C. Step 3 — 프로시저 / 펑션

### C-1. 트리 진입 (Database 패널)

| #  | 시나리오 | 기대 결과 |
|----|---------|----------|
| C1 | Oracle 데이터소스의 standalone PROCEDURE 우클릭 → Oracle Dictionary Info | 루틴 다이얼로그: Source(전체 본문) / Errors(0건) / Arguments 탭 표시 |
| C2 | Standalone FUNCTION에 동일 작업 | 좌상단에 `FUNCTION` 표시, Arguments에 position=0의 RETURN 행 포함 |
| C3 | **PACKAGE 내부의** 프로시저 우클릭 | "Oracle Dictionary Info" 메뉴 자체가 비활성/숨김 (`update()`에서 차단) |
| C4 | 컴파일 에러가 있는 PROCEDURE | 상단에 "컴파일 오류 N건" 표시, Errors 탭 채워짐 (line, position, text) |
| C5 | Oracle이 아닌 다른 DBMS의 PROCEDURE | 메뉴 비활성/숨김 |

### C-2. 에디터 진입

| #   | 시나리오 | 기대 결과 |
|-----|---------|----------|
| C6  | SQL 에디터에서 standalone 함수명 드래그 → `Alt+Shift+O` | 루틴 다이얼로그 오픈 |
| C6a | **드래그 없이 단어 중간/끝에 캐럿만 두고** `Alt+Shift+O` | 캐럿 위치 식별자가 자동 추출되어 다이얼로그 오픈 |
| C6b | 캐럿이 공백/구두점 위에 있을 때 (단어 위가 아님) `Alt+Shift+O` | 메뉴 비활성 (또는 동작 없음) |
| C6c | 식별자에 `_` / `#` / `$` 포함된 이름(예: `MY_PROC$2`) | 전체 식별자로 인식되어 정확히 검색 |
| C7  | 동일 이름의 TABLE과 PROCEDURE가 양쪽에 존재 (스키마 분리) | 선택 다이얼로그에 `HR.X  (TABLE)` / `HR.X  (PROCEDURE)` 표시, 선택 따라 적절한 다이얼로그 오픈 |
| C8  | 존재하지 않는 이름 드래그/캐럿 | 안내 메시지 "'XXX' 객체를 Oracle 데이터소스에서 찾을 수 없습니다." |
| C9  | 패키지 내부 루틴의 이름만 드래그/캐럿 | 검색 대상에서 제외되므로 C8과 동일 (안내 메시지) |

### C-2.5. Execute 탭 (실행 템플릿)

| #   | 시나리오 | 기대 결과 |
|-----|---------|----------|
| CE1 | IN 파라미터만 가진 PROCEDURE에서 Execute 탭 | `DECLARE l_xxx VARCHAR2(4000) := NULL; BEGIN HR.X(xxx => l_xxx); END; /` 형태, 복사 후 콘솔 붙여넣어 NULL 자리에 실제 값 채우면 실행 가능 |
| CE2 | OUT / IN OUT 파라미터 포함 PROCEDURE | 호출 뒤 `DBMS_OUTPUT.PUT_LINE('xxx: ' || l_xxx);` 라인이 OUT/INOUT 파라미터마다 추가됨 |
| CE3 | FUNCTION (RETURN 있음) | `l_result := HR.F(...);` + `DBMS_OUTPUT.PUT_LINE('RETURN: ' || l_result);` 포함 |
| CE4 | 파라미터가 0개인 PROCEDURE | `HR.X();` (괄호만), DECLARE 섹션 비어있음 (FUNCTION일 땐 `l_result`만 있음) |
| CE5 | Execute 탭 우상단 복사 버튼 클릭 | 클립보드 복사, 체크 아이콘 깜빡임 (Source/SQL 탭과 동일 동작) |
| CE6 | 복사한 블록을 DataGrip SQL 콘솔에 붙여넣고 `SET SERVEROUTPUT ON;` 포함 그대로 실행 | 정상 컴파일 후 IN 값에 따라 동작, OUT은 출력 패널의 DBMS_OUTPUT 으로 확인 |

### C-3. JDBC 폴백 / 새로고침 (루틴)

| #   | 시나리오 | 기대 결과 |
|-----|---------|----------|
| C10 | 루틴 다이얼로그 첫 진입 | 캐시에 소스가 없으므로 즉시 자동 JDBC 폴백, "캐시에 소스 없음 — 자동 새로고침" 상태 라벨 → 갱신 |
| C11 | 새로고침 버튼 클릭 | 백그라운드 작업, 같은 탭으로 복귀 |
| C12 | DB에서 본문 수정(`CREATE OR REPLACE`) 후 새로고침 | Source 탭에 변경 반영 |
| C13 | DB에서 의도적으로 컴파일 깨뜨린 후 새로고침 | Errors 탭에 행 추가, 상단 "컴파일 오류 N건" 갱신 |

---

## D. 안전성 / 회귀

| #  | 시나리오 | 기대 결과 |
|----|---------|----------|
| D1 | 모든 시나리오 수행 후 DB 감사 로그 확인 | `INSERT/UPDATE/DELETE/DDL` 전혀 없음 — `SELECT` 만 (이 플러그인의 핵심 제약) |
| D2 | 같은 객체 다이얼로그를 여러 개 동시에 열고 새로고침 동시 클릭 | 각 다이얼로그가 독립적으로 동작, 데드락 없음 |
| D3 | 다이얼로그 새로고침 중에 다이얼로그 닫기 | 백그라운드 작업이 종료되되 IDE 에러/예외 로그 없음 |

---

## 추후 개선 후보 (이번 단계 범위 외)
- 패키지(`PACKAGE` / `PACKAGE BODY`) 자체 지원 — 패키지 내 procedure/function 목록 탭
- `OracleTableInfoDialog` / `OracleRoutineInfoDialog` 공통 베이스(`OracleObjectInfoDialog`)로 추출 (현재는 헬퍼 코드 중복)
- 정렬 가능한 Arguments / Errors 탭 (TableRowSorter)
- Source 탭에 SQL syntax highlighting (현재는 plain JTextArea)
