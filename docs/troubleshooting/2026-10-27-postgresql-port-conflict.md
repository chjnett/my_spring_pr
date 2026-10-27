# PostgreSQL 컨테이너가 5432 포트를 점유해 SecureFlow DB를 시작하지 못한 문제

## 한 줄 요약

로컬에 이미 실행 중인 PostgreSQL Docker 컨테이너가 호스트 포트 `5432`를 사용하고 있었다. SecureFlow의 PostgreSQL은 호스트 포트를 `5433`으로 분리하고, 애플리케이션의 JDBC URL도 같은 포트를 보도록 변경했다.

## 증상

`docker compose up -d` 실행 시 다음 오류가 발생했다.

```text
Bind for 0.0.0.0:5432 failed: port is already allocated
```

## 재현 조건

- 호스트의 `5432` 포트에 PostgreSQL 또는 PostgreSQL Docker 컨테이너가 이미 바인딩되어 있음
- SecureFlow `compose.yaml`도 `5432:5432` 포트 매핑을 사용함

## 내가 처음 생각한 원인

SecureFlow Docker 설정이 잘못됐거나 PostgreSQL 컨테이너가 정상적으로 시작되지 않았다고 생각했다.

## 실제 원인

`lsof -nP -iTCP:5432 -sTCP:LISTEN`과 `docker ps`로 확인한 결과, 기존 `secureflow-postgres` 컨테이너가 이미 `0.0.0.0:5432->5432/tcp`를 사용 중이었다. Docker는 같은 호스트 포트를 두 컨테이너에 동시에 연결할 수 없다.

## 해결 과정

1. 기존 컨테이너는 중지하거나 삭제하지 않았다.
2. `compose.yaml`의 포트 매핑을 `5433:5432`로 변경했다.
3. `application.yml`의 JDBC URL을 `jdbc:postgresql://localhost:${DB_PORT:5433}/secureflow`로 변경했다.
4. `docker compose up -d`로 SecureFlow 전용 PostgreSQL을 다시 시작했다.

## 검증

```text
secureflow-postgres-1   postgres:16-alpine   0.0.0.0:5433->5432/tcp
/var/run/postgresql:5432 - accepting connections
```

컨테이너 내부 PostgreSQL은 기본 포트 `5432`로 동작하고, Mac에서 실행하는 Spring Boot 애플리케이션은 호스트 포트 `5433`으로 연결한다.

## 배운 점

- Docker 포트 표기 `호스트포트:컨테이너포트`에서 충돌은 왼쪽(호스트 포트)에서 발생한다.
- 기존 개발 환경을 무작정 중지하기보다 새 프로젝트에 다른 호스트 포트를 할당하면 안전하다.
- Docker Compose 설정과 애플리케이션 JDBC URL은 반드시 같은 접속 지점을 가리켜야 한다.

## 블로그 후보 여부

- [x] 아직 개인 학습 기록
- [ ] 기술 블로그 초안으로 발전 가능

포트 충돌 자체는 흔한 문제라 단독 글보다는, Docker Compose·Spring Boot·PostgreSQL 로컬 개발 환경을 정리하는 글의 한 사례로 묶는 편이 더 적합하다.
