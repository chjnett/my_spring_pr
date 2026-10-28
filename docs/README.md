# SecureFlow 문서 지도

SecureFlow는 기능 구현 기록을 쌓는 프로젝트가 아니다. 문제를 재현하고, 선택지를 비교하고, 테스트와 수치로 해결을 증명하는 백엔드 실습 프로젝트다.

## 문서 흐름

| 구분 | 목적 | 시작 문서 |
| --- | --- | --- |
| [스터디](study/README.md) | Spring Boot·JPA·HTTP의 기본 개념과 체크리스트 | [Spring Boot 기본기](study/01-spring-boot-basics.md) |
| [딥다이브](deep-dives/README.md) | 하나의 질문을 가설·실험·SQL 관찰로 파고들기 | [JPA 변경 감지](deep-dives/jpa-dirty-checking.md) |
| [프로젝트](project/README.md) | SecureFlow의 구조, 기술 스택, 개발 DB 전략 | [현재 아키텍처](project/architecture.md) |
| [사례 연구](case-studies/README.md) | PAAR로 정리한 문제 해결과 검증 수치 | [동시 가입과 UNIQUE](case-studies/concurrent-signup-unique-constraint.md) |
| [트러블슈팅](troubleshooting/README.md) | 시행착오를 포함한 원본 장애 기록 | [포트 충돌 기록](troubleshooting/2026-10-27-postgresql-port-conflict.md) |
| [템플릿](templates/PAAR.md) | 새 학습/블로그 후보 글을 빠짐없이 기록 | [PAAR 템플릿](templates/PAAR.md) |

## 새 기능을 시작할 때의 순서

1. 스터디 문서에서 기본 개념과 완료 기준을 확인한다.
2. 프로젝트 문서에서 현재 구조와 경계를 확인한다.
3. 실패 조건을 하나 정해 딥다이브 또는 사례 연구 문서를 만든다.
4. 테스트로 재현하고, 해결책을 비교한다.
5. PAAR 템플릿으로 문제·분석·행동·결과를 남긴다.
6. 재현 가능하고 수치가 있는 기록만 기술 블로그 후보로 발전시킨다.
