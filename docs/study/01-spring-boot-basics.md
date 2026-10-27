# 01. Spring Boot와 데이터베이스 기본기

이 문서는 SecureFlow 실습의 첫 번째 스프린트 체크리스트다. 구현을 끝내는 것보다, 아래 질문을 코드와 테스트로 설명하는 것을 완료 기준으로 삼는다.

## 이번 스프린트: User CRUD

### 구현 범위

- `POST /api/users`: 사용자 생성 (`201 Created`)
- `GET /api/users/{id}`: 단일 사용자 조회 (`200 OK`, 없으면 `404`)
- `GET /api/users`: 전체 사용자 조회
- `DELETE /api/users/{id}`: 사용자 삭제 (`204 No Content`)
- 유효하지 않은 요청은 `400 Bad Request`, 중복 이메일은 `409 Conflict`

## 반드시 설명할 질문

1. Controller, Service, Repository는 각각 무엇을 책임지는가?
2. Entity를 API 응답으로 직접 반환하지 않고 DTO를 쓰는 이유는 무엇인가?
3. `@Transactional` 안에서 예외가 발생하면 왜 저장한 데이터가 롤백되는가?
4. `save()` 직후 SQL `INSERT`가 항상 실행되는가? 영속성 컨텍스트와 flush를 연결해 설명할 수 있는가?
5. `existsByEmail()` 검사만으로 동시 가입을 완전히 막을 수 없는 이유는 무엇인가?

## 실습 순서

1. Docker로 PostgreSQL을 실행한다.
2. IntelliJ에서 애플리케이션을 실행하고 CRUD API를 호출한다.
3. `UserServiceTest`를 읽고, 성공·중복 이메일·없는 사용자 테스트를 직접 설명한다.
4. 잘못된 이메일 요청에서 `400`과 `VALIDATION_ERROR`가 나오는지 확인한다.
5. 다음 스프린트에서 동시 요청 테스트를 작성해 애플리케이션 사전 검사와 DB `UNIQUE` 제약의 역할 차이를 검증한다.

## 기록 템플릿

매번 아래 다섯 문장을 채운 뒤 대화로 가져온다.

```text
오늘 구현한 것:
막힌 지점 / 에러 메시지:
내가 생각한 원인:
테스트로 확인한 사실:
다음에 검증할 질문:
```
