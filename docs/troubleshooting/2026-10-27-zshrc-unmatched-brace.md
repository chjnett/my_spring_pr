# `.zshrc`의 남은 `}` 때문에 새 터미널이 파싱 오류를 내던 문제

## 한 줄 요약

`.zshrc`에 함수 선언부 없이 남은 명령 본문과 닫는 중괄호(`}`)가 여러 개 있어, 새 셸이 파일을 읽다가 `parse error near '}'`로 실패했다. 이미 아래쪽에 정의된 정상 함수는 유지하고, 중복된 깨진 블록만 제거했다.

## 증상

새 터미널을 열거나 `.zshrc`를 읽을 때 다음 오류가 나왔다.

```text
/Users/cheonhyeonjun/.zshrc:23: parse error near `}'
```

## 실제 원인

18~61줄에 `if`, `nohup`, `killall` 등의 함수 본문만 남아 있었고, 이를 감싸는 함수명과 여는 중괄호는 없었다. 반면 `deepbg`, `deepkeep`, `deepkill`은 파일 아래쪽에 정상 함수로 이미 존재했다.

## 해결 과정

1. 수정 전 파일을 `~/.zshrc.before-codex-fix-20261027`로 백업했다.
2. 함수 선언이 없는 중복 블록만 제거했다.
3. `zsh -n ~/.zshrc`로 문법 검사를 실행했다.
4. 새 zsh 프로세스에서 `deepbg`, `deepkeep`, `deepkill`, `JAVA_HOME`이 정상으로 로드되는지 확인했다.

## 검증

```text
deepbg: function
deepkeep: function
deepkill: function
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/.../Contents/Home
```

## 배운 점

- 셸 설정 파일은 작은 문법 오류 하나로 매 터미널 세션에 영향을 준다.
- 함수 블록을 추가할 때는 선언부와 닫는 중괄호를 함께 복사하고, 수정 직후 `zsh -n ~/.zshrc`로 검사한다.
- API 키 같은 비밀값을 셸 설정에 직접 두면 터미널 출력이나 화면 공유로 노출될 위험이 있으므로, 별도 비밀 관리 방식으로 옮기는 것이 좋다.

## 블로그 후보 여부

- [x] 아직 개인 학습 기록
- [ ] 기술 블로그 초안으로 발전 가능

일반적인 셸 설정 복구 사례라 단독 글보다는 개발 환경 초기화 체크리스트의 한 항목으로 쓰는 편이 적합하다.
