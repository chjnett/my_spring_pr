# 딥다이브

- [Idempotency-Key: 재시도와 새로운 중복 요청을 구분하기](idempotency-key.md)

딥다이브는 하나의 기술 질문을 가설과 실험으로 검증하는 기록이다. 결론만 쓰지 않고, 어떤 SQL·상태 변화·테스트가 결론을 뒷받침하는지 남긴다.

| 주제 | 핵심 질문 | 증거 |
| --- | --- | --- |
| [JPA 변경 감지](jpa-dirty-checking.md) | `save()` 없이 왜 `UPDATE`가 실행되는가? | managed/detached 테스트와 Hibernate SQL |
| [Idempotency-Key](idempotency-key.md) | 재시도와 새 중복 요청을 어떻게 구분하는가? | PostgreSQL 20개 동시 요청의 1회 처리·19회 재전송 |

## 작성 규칙

1. 질문을 한 문장으로 고정한다.
2. 먼저 가설을 쓴다.
3. 반증 가능한 실험을 작성한다.
4. 로그·SQL·테스트 결과를 근거로 결론을 쓴다.
5. 남은 질문을 다음 딥다이브로 연결한다.
