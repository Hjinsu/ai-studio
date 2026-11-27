# V2-05 외부 AI Worker 2차 검증

**PROPOSED · 실제 실행 보류** · 2026-11-27 KST · 기준 `883c3916ae0d6ce6a7690dd846a01cb36fdabacc`

- 로컬 상태 재확인. Codex는 ChatGPT, Claude Code는 claude.ai Pro/firstParty 로그인을 보고했다. 로컬 로그인 상태는 계정의 포함량, 잔액, 초과 과금 설정을 입증하지 않는다.
- Codex는 포함량 소진 후 계정에 사용 가능한 크레딧이 있으면 자동 소비할 수 있다. 자동 충전 OFF만으로 기존 크레딧 소비가 차단되지는 않는다. Claude는 Settings → Usage에서 usage credits를 끄면 포함량으로 제한된다. 계정 설정을 읽기 위해 접근 가능한 브라우저를 확인했으나 브라우저 연결 호스트를 사용할 수 없었다. 두 CLI 계정에 대응하는 설정 증거는 미확인이다. [OpenAI 크레딧](https://help.openai.com/en/articles/12642688-using-credits-for-flexible-usage-in-chatgpt-personal-plans) · [Claude usage credits](https://support.claude.com/en/articles/12429409-manage-usage-credits-for-paid-claude-plans)
- 기존 S1 TOPIC-A(자전 정지)·TOPIC-B(달의 망치와 깃털)는 동일 표본을 사용하도록 유지한다. 이번에는 Claude 초안과 Codex 검수본을 생성하지 않았다. 실제 JSON 수집·해시, 과학 정확성, 스토리텔링·유머, 40~60초 적합성, 이미지·동영상 연출, 실행 시간 및 구독 사용량은 모두 미측정이다. 1차 모의 파일 왕복 결과와 구별한다.
- 모델 실행·API 호출·자동 재시도·결제 설정 변경은 0건이다. 구독 한도 도달을 관찰하지 않았으므로 이번 보류는 한도 초과 WAIT가 아니다. 작업 스케줄러, 제품 코드, DB에는 변경이 없다. 무인 반복 및 제품 통합은 별도 승인 대기다.
- 재개 증거. Codex CLI 로그인과 같은 계정/워크스페이스에서 사용 가능한 추가 크레딧이 없고 자동 충전이 꺼져 있거나, 이 실행의 추가 크레딧 소비를 차단하는 계정 정책을 확인한다. Claude CLI 로그인과 같은 계정에서 usage credits OFF를 확인한다. 실행 직전 별도 API 키·대체 인증 미사용을 확인하고, 한도 초과 시 WAIT로 기록해 재호출 없이 종료한다.
