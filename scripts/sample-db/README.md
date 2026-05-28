# `scripts/sample-db/` — 데모/스크린샷용 Oracle 컨테이너

Marketplace 스크린샷, 새 기능 데모, 회귀 테스트(`TEST_SCENARIOS.md`)에 쓸 수 있는 **재현 가능한 Oracle 환경**을 한 명령으로 띄웁니다.

- 베이스 이미지: `gvenzl/oracle-free:23-slim-faststart` (Apple Silicon 지원)
- 자동 로드되는 객체: `HR.DEPARTMENTS`, `HR.EMPLOYEES`(1500행), 트리거, 뷰, 시퀀스, 시노님, 컴파일 오류 있는 프로시저, 4-함수 패키지, 옵티마이저 통계, HR에 `SELECT_CATALOG_ROLE + ALTER SYSTEM`

## 사전 준비 (한 번만)

```bash
brew install colima docker docker-compose
colima start --cpu 4 --memory 6 --disk 30
```

> 다른 Docker 런타임 (Docker Desktop, OrbStack) 도 동일하게 동작합니다.

## 컨테이너 띄우기

```bash
cd scripts/sample-db
docker compose up -d

# 부팅 진행 보기
docker compose logs -f
# → "DATABASE IS READY TO USE!" 메시지 보이면 준비 완료 (~1분)
# → 그 다음 init/setup/*.sql 들이 알파벳순으로 자동 실행됨 (~30초)
```

## DataGrip 연결

| 필드 | 값 |
|------|----|
| Host | `localhost` |
| Port | `1521` |
| Service | `FREEPDB1` |
| User | `HR` |
| Password | `hr` |

DBA 권한이 필요하면 (`SYS as SYSDBA`, password `oracle`).

## 스크린샷 시나리오 실행 (수동)

자동 로드되는 객체만으로는 부족한 **두 가지 동적 상황**은 수동 트리거:

### 1. 차단된 세션 만들기 (Sessions tool window 빨강 행)

DataGrip에서 **두 개의 콘솔**을 열고 `scenarios/blocking_session.sql` 안의 두 섹션을 각자 실행. 자세한 단계는 그 파일 주석 참조. 캡처 끝나면 `ROLLBACK;`.

### 2. Explain Plan 트리에 plan 채우기

DataGrip 콘솔에서 `scenarios/explain_plan_query.sql` 한 번 실행 → Oracle Sessions tool window에서 그 세션 선택 → Explain Plan sub-tab.

## 컨테이너 정리

```bash
cd scripts/sample-db

# 컨테이너만 정지 (다음에 다시 띄울 때 데이터 유지)
docker compose stop

# 완전 삭제 (다음에 다시 띄우면 init/setup 처음부터 재실행)
docker compose down -v
```

## 디렉터리 구조

```
scripts/sample-db/
├── compose.yaml                # Oracle 23ai Free 컨테이너 정의
├── init/setup/                 # 컨테이너 첫 부팅에서 알파벳순으로 자동 실행
│   ├── 01_schema.sql           # HR.* 테이블·뷰·트리거·시퀀스·시노님
│   ├── 02_data.sql             # 1500 직원 + 20 부서
│   ├── 03_buggy_proc.sql       # 컴파일 오류 있는 PROC + 정상 FUNC + IN/OUT PROC
│   ├── 04_demo_package.sql     # HR.HR_UTIL Spec + Body + 5개 routine
│   ├── 05_explain_stats.sql    # DBMS_STATS — Explain Plan 비용 정확도
│   └── 06_grants.sql           # HR에 SELECT_CATALOG_ROLE + ALTER SYSTEM
└── scenarios/                  # 캡처 시점에 수동 실행
    ├── blocking_session.sql
    └── explain_plan_query.sql
```

## 트러블슈팅

- **`Cannot connect to the Docker daemon`** — `colima start` 안 함. 위 사전 준비 다시.
- **포트 1521 충돌** — 다른 Oracle 인스턴스가 이미 1521 점유. `compose.yaml`의 ports를 `"15210:1521"`로 바꾸고 DataGrip Port도 같이 변경.
- **이미지 다운로드 너무 느림** — 2~3GB. 첫 한 번만 받음, 이후는 캐시.
- **메모리 부족** — `colima stop && colima start --memory 6` 로 늘림.
- **init SQL이 안 돌아간 것 같음** — `docker compose down -v` 로 볼륨까지 지운 뒤 `docker compose up -d` 재실행 (init은 첫 부팅에만 동작).
