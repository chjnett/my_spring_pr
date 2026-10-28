# SecureFlow

Spring Boot와 PostgreSQL로 데이터 정합성, 트랜잭션, 권한 부여를 단계적으로 실습하는 프로젝트다.

## IntelliJ 시작 방법

1. IntelliJ IDEA에서 이 `secureflow` 폴더를 **Open** 한다.
2. Gradle JVM을 **JDK 21**로 설정한다. (`Settings → Build Tools → Gradle`)
3. 터미널에서 PostgreSQL을 실행한다.

   ```bash
   docker compose up -d --wait
   ```

   SecureFlow은 호스트 포트 `5433`을 사용한다. 이미 다른 PostgreSQL이 `5432`를 쓰는 개발 환경과 충돌하지 않게 분리한 설정이다.

4. IntelliJ에서 `SecureflowApplication`을 실행하거나 터미널에서 실행한다.

   ```bash
   ./gradlew bootRun
   ```

5. 테스트를 실행한다.

   ```bash
   ./gradlew test
   ```

## 첫 API 호출

```bash
curl -i -X POST http://localhost:8081/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Hyeonjun","email":"hyeonjun@example.com"}'
```

문서의 시작점은 [docs/README.md](docs/README.md)다. 학습, 딥다이브, 프로젝트 설계, PAAR 사례, 트러블슈팅을 분리해 관리한다.
