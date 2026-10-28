# 개발용 PostgreSQL과 자동 테스트를 분리하는 이유

## 이번 단계의 목표

SecureFlow를 IntelliJ에서 실행할 때는 Docker Compose의 PostgreSQL을 사용한다. 반면 자동 테스트는 H2 또는 Testcontainers PostgreSQL을 사용한다.

둘은 같은 PostgreSQL이라는 단어를 쓰더라도 역할이 다르다. 개발 DB는 API를 직접 호출하고 데이터를 눈으로 확인하는 환경이고, 테스트 DB는 언제 실행해도 같은 결과가 나와야 하는 검증 환경이다.

```text
개발 환경
IntelliJ / bootRun → localhost:8081 → PostgreSQL container (localhost:5433)

자동 테스트
./gradlew test → Mock 또는 H2 / Testcontainers PostgreSQL → 테스트 종료 후 정리
```

## 설계 결정

| 구분 | 선택 | 이유 |
| --- | --- | --- |
| 개발 DB | Docker Compose PostgreSQL 16 | IntelliJ에서 API와 SQL을 반복 관찰할 수 있다. |
| 호스트 포트 | `5433` | 로컬에서 이미 사용 중일 수 있는 PostgreSQL 기본 포트 `5432`와 충돌하지 않는다. 컨테이너 안의 PostgreSQL은 계속 `5432`를 사용한다. |
| 빠른 테스트 | Mock/H2 | Service와 JPA 기본 동작을 빠르게 확인한다. |
| PostgreSQL 통합 테스트 | Testcontainers | UNIQUE 제약, SQLState, 동시성처럼 H2로는 믿기 어려운 동작을 실제 PostgreSQL에서 검증한다. |
| 데이터 초기화 | 테스트 컨테이너 단위 | 테스트가 개발 데이터에 의존하거나 개발 데이터를 지우지 않게 한다. |

## 시작 순서

### 1. 개발용 DB 시작

프로젝트 루트에서 실행한다.

```bash
docker compose up -d --wait
docker compose ps
```

`postgres`의 상태가 `healthy`이면 준비가 끝난 것이다.

`compose.yaml`의 포트 설정은 아래처럼 읽는다.

```text
호스트 localhost:5433 → 컨테이너 postgres:5432
```

따라서 SecureFlow의 기본 설정은 다음 DB에 연결된다.

```text
jdbc:postgresql://localhost:5433/secureflow
```

### 2. 애플리케이션 시작

IntelliJ에서 `SecureflowApplication`을 실행하거나 터미널에서 실행한다.

```bash
./gradlew bootRun
```

로그에 Hikari pool 시작과 Hibernate SQL이 보이면 연결을 확인할 수 있다. 애플리케이션 포트는 `8081`이다.

### 3. 실제 API를 한 번 검증

다른 터미널에서 실행한다.

```bash
curl -i -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Hyeonjun","email":"hyeonjun@example.com"}'
```

기대 결과는 `201 Created`다. 같은 이메일로 다시 요청하면 `409`와 `DUPLICATE_EMAIL` 계약을 확인한다.

```bash
curl -i -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Hyeonjun","email":"hyeonjun@example.com"}'
```

## 자동 테스트는 개발 DB를 사용하지 않는다

```bash
./gradlew test
```

현재 테스트 묶음에는 다음이 들어 있다.

| 테스트 | DB/도구 | 검증하는 것 |
| --- | --- | --- |
| `UserServiceTest` | Mockito | 중복 이메일 예외 변환, Repository 호출 계약 |
| `UserDirtyCheckingTest` | H2 + `@DataJpaTest` | managed/detached Entity와 변경 감지 |
| `DuplicateEmailConcurrencyPostgresTest` | Testcontainers PostgreSQL 16 | 동시 가입 20건에서 UNIQUE 제약이 한 건만 저장하는지 |

Testcontainers 테스트는 Docker를 사용하지만 Compose의 `secureflow-postgres` 컨테이너를 재사용하지 않는다. 테스트 전용 PostgreSQL 컨테이너를 만들고, 테스트가 끝나면 정리한다.

## 매일의 개발 루틴

1. `docker compose up -d --wait`로 개발 DB를 준비한다.
2. IntelliJ에서 기능을 구현하고 실제 API를 호출한다.
3. 실패 조건을 하나 정하고 테스트로 먼저 재현한다.
4. `./gradlew test`로 전체 회귀를 확인한다.
5. 관찰한 SQL, 성공/실패 건수, 선택한 해결책을 학습 문서와 기술 블로그 초안에 남긴다.

## 완료 기준

- [ ] `docker compose ps`에서 PostgreSQL이 `healthy`다.
- [ ] IntelliJ 또는 `./gradlew bootRun`으로 SecureFlow가 `8081`에서 실행된다.
- [ ] 사용자 생성 요청이 개발용 PostgreSQL에 저장된다.
- [ ] 같은 이메일 요청은 `409 DUPLICATE_EMAIL`을 반환한다.
- [ ] `./gradlew test`가 통과한다.
- [ ] Testcontainers 동시성 테스트 결과가 `total=20, success=1, unique_conflicts=19, persisted=1`이다.

## 다음 딥다이브

개발 DB에서 역할 부여 API를 만든 뒤, 같은 요청을 동시에 보내 중복 권한 부여를 재현한다. 그 다음 PostgreSQL UNIQUE 제약, 멱등성 키, 충돌 응답 정책을 각각 비교한다.
