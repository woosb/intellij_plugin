# Oracle Dictionary Inspector — 테스트 시나리오

이 문서는 플러그인 전체 기능에 대한 **수동 회귀 테스트** 절차입니다. 모든 케이스는 실제 IDE에서 직접 수행해야 합니다 (UI 동작은 컴파일러로 확인 불가). 자동화는 향후 작업.

> ⚠ **읽기 전용 원칙**: 어떤 시나리오에서도 DB 데이터/스키마가 변경되어서는 안 됩니다. 유일한 예외는 명시적으로 표시된 `Kill Session` 케이스 — 운영 환경 절대 금지.

## 사전 조건

1. **샘플 Oracle 데이터소스 1개** — 사용자 권한:
   ```sql
   GRANT SELECT_CATALOG_ROLE TO <user>;   -- V$* / ALL_* / DBA_*
   GRANT ALTER SYSTEM TO <user>;          -- Kill Session 시나리오용 (개발 환경만)
   ```
2. **샘플 객체** — 최소 다음 세트:
   - 테이블 `HR.EMPLOYEES` (PK·FK·Index·트리거 1개 이상, 데이터 1000행 이상)
   - 뷰 `HR.EMP_DETAILS_VIEW`
   - 표준 프로시저 `HR.ADD_JOB_HISTORY` (IN/OUT 파라미터 혼합)
   - 표준 펑션 `HR.GET_FULL_NAME` (RETURN 있음)
   - 컴파일 오류가 있는 객체 1개 (의도적으로 PL/SQL 깨뜨려 둠)
   - 패키지 `HR.HR_UTIL` (Spec + Body + 내부 routine 2개 이상)
   - 시퀀스 `HR.EMPLOYEES_SEQ`
   - 시노님 `HR.PUBLIC_EMP_SYN` (참조: `HR.EMPLOYEES`)
3. **IDE 환경**: DataGrip 또는 IntelliJ IDEA Ultimate 2024.3+, 위 데이터소스 connection 완료
4. **빌드**: `./gradlew runIde` 로 샌드박스 IDE 실행 또는 빌드된 zip 설치

각 시나리오는 **Given / When / Then** 형식. **G**=초기 상태, **W**=동작, **T**=기대 결과.

---

## A. 진입점 / 액션

### A1. DB 패널 우클릭 — 모든 객체 종류 인식
- **G**: Database 트리에 위 샘플 객체 모두 표시
- **W**: 각 객체 노드 우클릭
- **T**: 다음 모든 종류에서 **"Oracle Dictionary Info"** 메뉴가 보임
  - [ ] TABLE
  - [ ] VIEW
  - [ ] PROCEDURE (standalone)
  - [ ] FUNCTION (standalone)
  - [ ] PACKAGE
  - [ ] SEQUENCE
  - [ ] SYNONYM
- [ ] 패키지 내부 PROCEDURE 노드에서는 메뉴 **비활성/숨김** (`update()` 차단)
- [ ] Oracle 아닌 다른 DBMS 객체에서는 메뉴 **비활성/숨김**

### A2. DB 패널 단축키
- **G**: TABLE 노드 선택
- **W**: `Alt+Shift+O` (macOS: `⌥+Shift+O`)
- **T**: 해당 객체의 다이얼로그가 우클릭→메뉴와 동일하게 열림

### A3. SQL 에디터 — 드래그 선택
- **G**: SQL 콘솔에 `SELECT * FROM HR.EMPLOYEES WHERE EMPLOYEE_ID=100;` 입력
- **W**: `EMPLOYEES`만 드래그 선택 → `Alt+Shift+O`
- **T**: 테이블 다이얼로그 오픈, 제목 `HR.EMPLOYEES`

### A4. SQL 에디터 — 캐럿 위치만
- **G**: 같은 SQL
- **W**: `EMPLOYEES` 단어 중간 어딘가에 캐럿 두고 (드래그 선택 X) `Alt+Shift+O`
- **T**: 단어 경계 자동 추출 → 다이얼로그 오픈

### A5. SQL 에디터 — SCHEMA.NAME 명시
- **G**: `HR.EMPLOYEES`에 캐럿
- **W**: `Alt+Shift+O`
- **T**: OWNER `HR`만 후보로 검색 → 바로 다이얼로그

### A6. SQL 에디터 — 식별자에 `_` `#` `$` 포함
- **G**: `MY_PKG$2` 같은 이름의 객체가 DB에 있다고 가정 (테스트용 생성 또는 mock)
- **W**: 그 단어에 캐럿 두고 `Alt+Shift+O`
- **T**: 전체 식별자로 검색됨 (구두점에서 끊기지 않음)

### A7. SQL 에디터 — 존재하지 않는 이름
- **G**: `NOSUCH_OBJECT` 텍스트
- **W**: `Alt+Shift+O`
- **T**: 메시지 박스 — `'NOSUCH_OBJECT' 객체를 Oracle 데이터소스에서 찾을 수 없습니다.` (한국어 locale) 또는 영문 동일 메시지

### A8. SQL 에디터 — 동명이 객체 여러 스키마
- **G**: 두 스키마에 `X` 라는 이름의 TABLE이 있는 환경
- **W**: `X`에 캐럿 → `Alt+Shift+O`
- **T**: **선택 다이얼로그**에 `SCHEMA1.X (TABLE)` / `SCHEMA2.X (TABLE)` — 선택한 쪽으로 진입

### A9. 다이얼로그 안 Source 에디터에서 또 다른 객체 진입
- **G**: `HR.HR_UTIL` 패키지 다이얼로그 Spec 탭, 본문에 `HR.EMPLOYEES` 참조가 있음
- **W**: 그 위에 캐럿 → `Alt+Shift+O`
- **T**: `HR.EMPLOYEES` 테이블 다이얼로그가 **별도 비모달**로 또 열림 (기존 패키지 다이얼로그 유지)

### A10. 패키지 내부 routine 검색 제외
- **G**: 패키지 `HR_UTIL` 안에 `LOG_EVENT` 프로시저 존재 (standalone 동명 없음)
- **W**: SQL 에디터에서 `LOG_EVENT` 드래그 → `Alt+Shift+O`
- **T**: A7과 동일 — 찾을 수 없음 메시지 (패키지 내부는 검색 대상 아님)

---

## B. 테이블 다이얼로그

### B1. 모든 탭 표시 + 순서
- **G**: `HR.EMPLOYEES` 테이블 다이얼로그 진입
- **W**: 탭 바 확인
- **T**: 다음 순서로 탭이 표시됨
  ```
  Columns | Keys | Foreign Keys | Indexes | Checks | Triggers | Data | DDL | SELECT | Comments SQL
  ```
  (이전 라운드까지 있던 "Triggers SQL"은 사라짐)

### B2. Columns 탭 — 데이터 무결성
- **G**: B1 상태
- **W**: Columns 탭 진입
- **T**: 컬럼 수와 EMPLOYEES 실제 컬럼 수 일치, PK 컬럼은 PK열에 1/2/… 표시, 인덱스 포함 컬럼은 Index열에 `●`

### B3. Columns 탭 — 정렬 사이클
- **W**: 임의 컬럼 헤더 클릭 → 오름차순 → 다시 클릭 → 내림차순 → 다시 클릭 → **미정렬(원래 순서)**
- **T**: 3단계 사이클 정상

### B4. Triggers 탭 — 실제 데이터 (Round 4)
- **G**: EMPLOYEES에 트리거 존재
- **W**: Triggers 탭 진입
- **T**: 트리거 행 표시 — `Trigger Name / Type / Event / Status / Action Type`. 데이터는 `ALL_TRIGGERS` 값 그대로

### B5. Data 탭 — 첫 페이지 자동 로드
- **W**: Data 탭을 처음 클릭
- **T**: 백그라운드 progress bar "데이터 조회 (페이지 1)" → 첫 500행 표시. 페이지 라벨 `페이지 1`, 행 카운트 `행 1-500 (더 있음)`

### B6. Data 탭 — 페이지 이동
- **W**: `→` 클릭
- **T**: 페이지 2 로드, `행 501-1000 (더 있음)`, `←` 버튼 활성

### B7. Data 탭 — 마지막 페이지
- **W**: 데이터 끝까지 `→` 반복
- **T**: 마지막 페이지에서 `(더 있음)` 사라짐, `→` 버튼 **비활성**

### B8. Data 탭 — WHERE 필터
- **W**: WHERE 입력란에 `EMPLOYEE_ID > 100` 입력 → **Enter**
- **T**: 페이지 1부터 다시 로드, EMPLOYEE_ID > 100만 표시. WHERE 비우고 Enter → 필터 해제

### B9. Data 탭 — ORDER BY
- **W**: ORDER BY에 `EMPLOYEE_ID DESC` → Enter
- **T**: EMPLOYEE_ID 내림차순으로 정렬

### B10. Data 탭 — 잘못된 WHERE
- **W**: WHERE에 `EMPLOYEE_ID == 100` (잘못된 문법) → Enter
- **T**: 우상단 알림 "데이터 조회 실패: ORA-…" + 행 카운트 `오류` 표시. 정상 WHERE로 다시 입력하면 복구

### B11. Data 탭 — Oracle 벤더 타입 표시
- **G**: 테스트용 테이블에 `TIMESTAMP WITH TIME ZONE` 컬럼 존재
- **T**: 해당 컬럼 값이 정상 문자열로 표시. `<failed to load> oracle.sql.TIMESTAMPTZ` 같은 표시 **없음**

### B12. Data 탭 — BLOB 컬럼
- **G**: BLOB 컬럼 존재
- **T**: 값이 `<BINARY N bytes>` placeholder로 표시 (실제 byte 수)

### B13. DDL 탭 (테이블)
- **T**: `CREATE TABLE HR.EMPLOYEES (...);` 구조 정상, 컬럼 코멘트 있으면 `COMMENT ON COLUMN …` 추가

### B14. SELECT 탭
- **T**: `SELECT col1, col2, ... FROM HR.EMPLOYEES;` 형태, 복사 버튼 동작 + 체크 아이콘 1.2초 깜빡임

### B15. Comments SQL 탭
- **T**: `ALL_COL_COMMENTS` 조회 텍스트 표시 (이건 실제 조회 안 함, 텍스트 스니펫)

---

## C. 뷰 다이얼로그

### C1. 진입
- **G**: `HR.EMP_DETAILS_VIEW`
- **T**: 테이블과 같은 다이얼로그 오픈. Columns/Triggers/Data/DDL/SELECT/Comments SQL 표시

### C2. Keys / FK / Indexes / Checks 빈 상태
- **T**: 보통 비어있음 (뷰는 제약이 없음). 빈 테이블이 어색하지 않게 표시되면 OK

### C3. DDL 탭 — VIEW 본문 (Round 4)
- **W**: DDL 탭
- **T**: `CREATE OR REPLACE VIEW HR.EMP_DETAILS_VIEW AS <ALL_VIEWS.TEXT>;` 형태. `CREATE TABLE` 아님 ← **꼭 확인**

### C4. 권한 부족 폴백
- **G**: `ALL_VIEWS` 접근권 없는 사용자
- **T**: DDL 본문 자리에 "VIEW 본문 미수집…" 안내. 에러로 다이얼로그 닫히지 않음

### C5. Triggers 탭 (INSTEAD OF)
- **G**: 뷰에 INSTEAD OF 트리거 존재
- **T**: 정상 표시

---

## D. 프로시저 / 펑션 다이얼로그

### D1. 진입 + 자동 JDBC 폴백
- **G**: `HR.GET_FULL_NAME` 함수, 캐시 비어있음
- **W**: 다이얼로그 진입
- **T**: 상태 라벨 "캐시에 소스 없음 — 자동 새로고침" → 잠시 후 Source 탭 채워짐

### D2. Source 탭 — IntelliJ Editor 기능 (Round 3)
- **T**: 좌측에 라인 번호 / SQL 신택스 하이라이팅 / 캐럿 행 강조 / 폴딩

### D3. Source 탭 — 컴파일 오류 강조
- **G**: 깨진 PL/SQL 객체
- **T**:
  - [ ] 상단 라벨 `PROCEDURE • 컴파일 오류 N건` 표시
  - [ ] 오류 라인의 배경이 **빨강**
  - [ ] 우측 에러 스트라이프에 빨간 마크 표시
  - [ ] 그 마크에 마우스 호버 시 툴팁 `Line N, col M: <오류 메시지>`

### D4. Errors 탭 더블클릭 → Source 점프
- **W**: Errors 탭 → 첫 행 더블클릭
- **T**: Source 탭으로 자동 전환, 캐럿이 해당 라인 시작으로 이동, 스크롤 중앙 정렬

### D5. Execute 탭 — IN 파라미터만
- **G**: IN 두 개
- **T**: `DECLARE l_x VARCHAR2(4000) := NULL; l_y NUMBER := NULL; BEGIN HR.PROC_NAME(x => l_x, y => l_y); END; /` 형태

### D6. Execute 탭 — OUT/IN OUT 포함
- **T**: 호출 후 `DBMS_OUTPUT.PUT_LINE('x: ' || l_x);` 라인이 OUT/INOUT마다 추가

### D7. Execute 탭 — FUNCTION RETURN
- **T**: `l_result := HR.FUNC(...);` + `DBMS_OUTPUT.PUT_LINE('RETURN: ' || l_result);`

### D8. Execute 탭 — 0 매개변수
- **T**: `HR.PROC();` (괄호만)

### D9. Arguments 탭
- **T**: position / name / direction(IN/OUT/INOUT/RETURN) / data type / default 정확히 표시

### D10. 새로고침 — Source 변경 반영
- **W**: DB에서 `CREATE OR REPLACE FUNCTION …` 실행 후 다이얼로그 새로고침 버튼
- **T**: Source 탭에 새 본문 즉시 표시

---

## E. 패키지 다이얼로그 (Round 4)

### E1. 진입 + 자동 폴백
- **G**: `HR.HR_UTIL`
- **T**: Spec 탭에 자동으로 `ALL_SOURCE TYPE='PACKAGE'` 본문 표시

### E2. Body 탭
- **T**: `ALL_SOURCE TYPE='PACKAGE BODY'` 본문 표시. Body 없는 패키지면 안내 텍스트

### E3. Routines 탭
- **T**: 내부 PROCEDURE/FUNCTION 목록 — Name / Overload / Kind

### E4. Errors 탭 — Spec vs Body 분리
- **G**: 패키지 Body에 컴파일 오류 존재
- **T**:
  - [ ] Errors 탭 행에 sourceType=`PACKAGE BODY` 표시
  - [ ] Body 탭의 해당 라인만 빨강 강조 (Spec 탭은 깨끗)
  - [ ] Errors 행 더블클릭 → **Body 탭**으로 점프 (Spec 아님)

### E5. Spec과 Body 둘 다 오류
- **T**: 각 탭이 자기 sourceType 오류만 강조

---

## F. SEQUENCE / SYNONYM 다이얼로그 (Round 4)

### F1. Sequence 진입
- **G**: `HR.EMPLOYEES_SEQ`
- **T**: Property/Value 테이블 — Owner / Name / Min Value / Max Value / Increment By / Cycle / Order / Cache Size / Last Number. 값 정확

### F2. Sequence — Cycle/Order 표시
- **T**: `Cycle: YES/NO`, `Order: YES/NO` 텍스트 (Boolean을 그대로 노출하지 않음)

### F3. Synonym 진입
- **G**: `HR.PUBLIC_EMP_SYN` (참조 `HR.EMPLOYEES`)
- **T**: Owner / Name / References (`HR.EMPLOYEES`) / DB Link (없으면 표시 안 함)

### F4. Synonym — DB Link 있는 경우
- **G**: DB Link 사용 synonym
- **T**: DB Link 행 표시

### F5. 새로고침
- **T**: 동일 데이터 재조회 (캐시 갱신)

---

## G. Oracle Sessions Tool Window

### G1. Tool Window 표시
- **W**: IDE 하단의 **Oracle Sessions** 아이콘 클릭
- **T**: 패널 열림, 상단 툴바 + 3개 탭 (Sessions / Locks / Long Ops)

### G2. DataSource 콤보
- **T**: 프로젝트의 Oracle 데이터소스만 노출. 첫 항목 자동 선택 → 자동 로드

### G3. Auto 5s 토글
- **W**: Auto 5s 체크
- **T**: 5초마다 활성 탭만 자동 새로고침. 다시 클릭하면 정지

### G4. Background 토글
- **T**: 체크 시 `TYPE='BACKGROUND'` 세션도 Sessions 탭에 포함 (SMON/PMON 등). 해제 시 사라짐

### G5. Sessions 탭 — 차단 세션 강조
- **G**: 한 세션이 다른 세션을 차단 중인 환경
- **T**: 차단된 세션 행의 배경이 **빨강**, ACTIVE 세션 글자 **초록**

### G6. Sessions 탭 — 필터 행 (Round 4)
- **W**: USER 입력 `HR`, STATUS `ACTIVE` 입력
- **T**: 즉시(서버 재조회 없이) 두 조건 AND 만족 행만 표시. `Clear` 누르면 모두 비움

### G7. Sessions 탭 우클릭 — Kill Session
- **W**: 우클릭 → "Kill Session SID,SERIAL# (USER)"
- **T**: 확인 다이얼로그에 SID/SERIAL/USER/MACHINE/PROGRAM/STATUS + 실행될 SQL `ALTER SYSTEM KILL SESSION 'X,Y' IMMEDIATE` 표시. `KILL` 클릭 시 실행, `취소` 가능
- **⚠ 운영 환경 절대 금지** — 개발 환경에서 본인 테스트 세션만 대상

### G8. Sessions 탭 하단 sub-tab — Current SQL (Round 3)
- **W**: 세션 행 선택
- **T**: 하단 Current SQL 탭에 `V$SQLAREA.SQL_FULLTEXT` 표시 (IntelliJ Editor, 신택스 적용). SQL_ID 없으면 "이 세션의 현재 SQL_ID 없음" 메시지

### G9. Wait History sub-tab (Round 5)
- **T**: 세션 선택 → Wait History 탭에 최근 10건 wait event (`V$SESSION_WAIT_HISTORY`) — SEQ#/EVENT/WAIT_TIME(cs)/P1/P2/P3

### G10. Session Stats sub-tab (Round 5)
- **T**: 자주 보는 통계 ~15개가 알파벳 순으로. 큰 값은 천 단위 콤마

### G11. Explain Plan sub-tab (Round 5)
- **G**: 세션이 SQL_ID가 있는 ACTIVE 상태
- **T**:
  - [ ] ID / Operation (depth만큼 들여쓰기) / Object / Rows / Bytes / Cost / CPU / Time 표시
  - [ ] `TABLE ACCESS FULL` 행의 배경이 **빨강**
  - [ ] `INDEX RANGE SCAN` 같은 행의 글자 **초록**
  - [ ] SQL_ID 없는 세션 선택 시 빈 테이블

### G12. Locks 탭
- **G**: 잠금 발생 환경 (개발용으로 의도적 생성 가능: `SELECT … FOR UPDATE` 후 미커밋)
- **T**: V$LOCKED_OBJECT 행 표시. `Exclusive` / `Row-X` 행 **빨강** 강조. 우클릭 → "Kill Holder Session"

### G13. Long Ops 탭
- **G**: 큰 INSERT/UPDATE 또는 통계 수집 등 장기 작업 실행 중
- **T**: 진행률 `0..100%`, SOFAR/TOTAL/UNITS/ELAPSED/REMAINING 표시. 완료된 작업은 자동 사라짐

### G14. 권한 부족 처리
- **G**: `V$SESSION` 권한 없는 사용자
- **T**: 우상단 알림 BALLOON — `V$ 뷰 SELECT 권한이 없습니다. … GRANT SELECT_CATALOG_ROLE …` 멀티라인 안내

---

## H. 인라인 검색 / 컬럼 너비 / 다국어 (Round 5)

### H1. 인라인 검색 — 테이블 다이얼로그 Columns
- **G**: EMPLOYEES Columns 탭, 컬럼 30개
- **W**: 테이블에 포커스 두고 `created_at` 타이핑
- **T**: 매칭 행으로 점프 + 강조

### H2. 인라인 검색 — Sessions / Locks / Long Ops / Wait / Stats / Plan 모든 테이블
- **T**: H1과 동일하게 동작

### H3. Source 탭 IDE Find Bar
- **G**: 패키지 Spec 탭
- **W**: 포커스 둔 후 `Cmd+F` (macOS) / `Ctrl+F`
- **T**: IDE 기본 Find Bar 상단에 슬라이드 인 (Source 탭은 IntelliJ Editor라 자동 동작)

### H4. 컬럼 너비 저장 — 변경 후 IDE 재시작
- **W**: Columns 탭에서 임의 컬럼 너비 마우스로 드래그 변경 → 다이얼로그 닫기 → 같은 객체 다시 열기
- **T**: 변경된 너비 그대로 유지

### H5. 컬럼 너비 — IDE 재시작 후
- **W**: H4 후 IDE 완전 종료 → 재시작 → 같은 객체 다이얼로그
- **T**: 너비 유지 (PropertiesComponent 영속)

### H6. 다국어 — 영문 locale
- **G**: 기본 (영문) locale
- **T**: 모든 라벨/툴팁/알림이 영문 — "Refresh", "Copy to clipboard", "Sessions", etc.

### H7. 다국어 — 한국어 locale
- **W**: Help → Edit Custom VM Options → `-Duser.language=ko` 추가 → IDE 재시작
- **T**: 모든 라벨/툴팁/알림이 한국어 — "새로고침", "클립보드에 복사", "지금 새로고침", etc.

### H8. 다국어 — 누락 키 폴백
- **G**: properties에 의도적으로 한 키만 `_ko`에서 제거
- **T**: 그 키만 영문, 나머지는 한국어 — 자동 폴백

---

## I. 회귀 / 안전성

### I1. 읽기 전용 검증
- **G**: Oracle DB Audit이 켜진 환경 (`AUDIT_TRAIL='DB'`)
- **W**: 본 문서의 A~H 모든 시나리오 수행 후 `DBA_AUDIT_TRAIL` 조회
- **T**: 우리 사용자 명의로 발생한 작업이 모두 **SELECT** — `INSERT/UPDATE/DELETE/DDL` 0건. (Kill Session은 별도 — `ALTER SYSTEM`)

### I2. 동시 다이얼로그
- **W**: 같은 테이블 다이얼로그 3개 동시 오픈 → 각각 새로고침 동시 클릭
- **T**: 각 다이얼로그 독립 동작, 데드락 없음, IDE 에러 로그 없음

### I3. 다이얼로그 닫는 중 백그라운드 작업
- **W**: 큰 테이블의 Data 탭 로딩 중 (수 초 걸리는 케이스) 다이얼로그 닫기
- **T**: IDE 예외 없음, 백그라운드 task가 안전하게 종료

### I4. 새로고침 연타
- **W**: 새로고침 버튼 빠르게 여러 번 클릭
- **T**: 추가 클릭은 무시됨 (loading 가드)

### I5. 데이터소스 비밀번호 미입력
- **G**: IDE 시작 직후, 데이터소스 비밀번호 캐시 없음
- **W**: 다이얼로그 진입 → JDBC 폴백 시도
- **T**: DataGrip 표준 비밀번호 프롬프트, 사용자가 취소하면 알림 "DB 조회 실패: ..." (다이얼로그는 닫히지 않고 빈 상태 유지)

### I6. ship.sh 스크립트 (개발자 워크플로우)
- **G**: 개발자가 feature 브랜치에서 작업 + 커밋 완료
- **W**: `./scripts/ship.sh`
- **T**: push → PR 생성 → squash merge → 원격 브랜치 삭제 한 방. 로컬 브랜치는 남아있고 안내 메시지 출력

---

## J. 출시 전 최종 점검 (Round 7 이후)

### J1. `./gradlew verifyPlugin`
- **T**: IntelliJ Platform Plugin Verifier 통과 (다양한 IDE 버전과 호환)

### J2. plugin.xml 필수 필드
- [ ] `<vendor email>` 채워짐
- [ ] `<vendor url>` 채워짐 (예: GitHub repo URL)
- [ ] `<description>` 영문 + 한국어 dual content
- [ ] `<change-notes>` 또는 외부 CHANGELOG 링크

### J3. 아이콘
- [ ] `META-INF/pluginIcon.svg` 존재 (라이트 테마)
- [ ] `META-INF/pluginIcon_dark.svg` 존재 (다크 테마)
- [ ] 40×40 권장, 시각적으로 식별 가능

### J4. LICENSE
- [ ] 루트에 LICENSE 파일 존재 (MIT 또는 Apache 2.0)
- [ ] README 라이선스 섹션이 LICENSE 파일과 일치

### J5. 버전
- [ ] `build.gradle.kts`의 `version`이 `*-SNAPSHOT` 아님
- [ ] semver 준수

### J6. Marketplace 업로드 리허설
- **W**: `./gradlew buildPlugin` → 생성된 zip을 다른 PC의 IDE에 **Install Plugin from Disk**
- **T**: 정상 설치 + 모든 기능 동작 (이 문서의 A~I 핵심 시나리오 재수행)

---

## 부록: 시나리오 우선순위

릴리스 가능성을 빠르게 판단할 때:

| 우선순위 | 시나리오 | 이유 |
|---------|---------|------|
| P0 (필수) | A1, A2, B1, B5, B8, C3, D2, D3, D4, E1, E4, G1, G7, H1, I1 | 핵심 기능 + 안전성 |
| P1 (중요) | A3-A10, B6-B14, C1-C5, D5-D10, E2-E5, F1-F5, G2-G14 | 풍부한 기능 검증 |
| P2 (보강) | H2-H8, I2-I6 | 사용성/회귀 |
| 출시 | J1-J6 | Round 7 이후 |

P0만 통과해도 **개발 환경에서 일상 사용 가능 상태**. P1까지 통과하면 **사내 배포 OK**. P2 + J까지 통과해야 **JetBrains Marketplace 공개 출시 OK**.
