# AI Studio — 설계 기준선 v0.1
AI 영상 자동 제작 및 유튜브 운영 플랫폼. 현재는 설계 패키지이며 실행 가능한 앱은 아직 없다.
## 시작
1. 이 폴더를 Git 저장소 루트에 둔다(숨김 폴더 포함). 기존 프로젝트라면 충돌 검토 후 병합한다.
2. Git으로 비공개 원격 저장소에 저장한다. 다른 PC에서 clone/pull 후 동일 루트에서 Claude Code 또는 Codex를 실행한다.
3. Claude Code: `/ai-studio` 또는 “STATE.md의 다음 작업 진행”. Codex: `$ai-studio` 또는 같은 요청.
4. 각 PC에 Git, JDK 21, Node.js 호환 버전, Docker, FFmpeg/FFprobe를 설치한다. 버전 고정은 T01에서 수행한다.
5. `.env.example`을 `.env`로 복사하고 필요한 값만 로컬에 입력한다. 토큰과 DB/미디어는 Git으로 동기화하지 않는다. 별도 암호화 백업/공유 스토리지 필요.
6. 한 작업 종료 후 STATE.md 갱신, 검토 후 commit/push. 다른 PC에서는 pull 후 재개. 동시에 같은 작업을 수정하지 않는다.
## 문서
[설계 목차](docs/00_INDEX.md), [작업 상태](STATE.md), [결정 기록](DECISIONS.md), [공통 계약](AGENTS.md).
## 초기 설정
단일 영어 과학 채널은 제안값. 쇼츠 40~60초, 롱폼 8~12분, 월 API 예산 30만원은 가설이며 실측 전 확정 비용이 아니다. 실제 계정 연동/게시/수익화는 미검증.
