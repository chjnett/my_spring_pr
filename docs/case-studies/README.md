# 사례 연구: PAAR

사례 연구는 구현 목록이 아니라 문제 해결의 증거다. 아래 네 요소를 모두 채운 기록만 이 폴더에 둔다.

- **P — Problem:** 왜 이 문제가 중요한가?
- **A — Analyze:** 어떤 선택지 중 무엇을 왜 선택했는가?
- **A — Action:** 코드·스키마·테스트에 어떤 행동을 했는가?
- **R — Result:** 어떤 테스트·로그·수치로 결과를 증명했는가?

| 사례 | 핵심 증거 |
| --- | --- |
| [동시 가입과 PostgreSQL UNIQUE](concurrent-signup-unique-constraint.md) | 동시 요청 20건 → 저장 1건, UNIQUE 충돌 19건 |
| [사용자 수정 API와 변경 감지](user-update-api.md) | `save()` 없이 변경 감지, 성공·실패 단위 테스트 |
