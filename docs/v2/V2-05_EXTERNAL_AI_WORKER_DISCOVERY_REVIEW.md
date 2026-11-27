# V2-05 외부 AI Worker 소재 발굴 확장 검토

**PROPOSED** · 2026-11-27 KST · 기준 `f1cf236c55932d6d4d22989403b286a81b1133f9`

[외부 Worker 검증 계획](V2-05_EXTERNAL_AI_WORKER_VALIDATION_PLAN.md) · [소재 발굴 기존 계약](07_TOPIC_DISCOVERY_AND_API.md)

## 사용자 승인 및 후속 반영

사용자는 기존 DISCOVER·SELECT, 파일 Worker, MariaDB 상태 관리와 fencing의 재사용을 승인했다. 소재 조사·후보 발굴·대본·검수·연출은 구독 기반 Worker로 확장하는 방향을 유지한다. 채널별 예약과 무인 반복은 V2-07 범위다. 공식 이용 조건이 불명확한 무인 실행은 보류한다. 유료 검색·LLM API 자동 전환과 미확인 출처·트렌드 생성은 금지한다. 설계 상태는 PROPOSED다.

독립 대본 검수 2건은 완료됐다. Claude의 S2~S4 미디어 실험 완료 후 전체 결과를 V2-05 최종 설계에 반영한다. 이번 승인으로 모델 실행·제품 구현·DB 변경·스케줄러 등록을 시작하지 않는다. [독립 검수 결과](../reports/V2-05_EXTERNAL_AI_WORKER_ROUND2.md)

## 기존 구현과 제안 범위

- **DISCOVER**는 `ProductionDiscovery.Input`의 요청·채널 설정·언어·후보 수·길이·전략·시청자·금지 주제 스냅샷을 사용한다. `DiscoveryResponseCodec/Validator`가 구조를 검사하고, `RuleBasedDiscoveryEvaluator`가 서버 점수와 중복·유사성 불확실·금지·미입증 주장 차단을 결정한다. `ProductionDiscoveryService`는 기존 배치·후보·응답 해시를 보유한다. 현재 DISCOVER 적용 경계는 비용 operation 영수증과 결합돼 있으므로, 구독 모드는 기존 비동기 파일 실행 경계에서 검증된 결과를 같은 평가·채택 경로에 연결하는 설계가 필요하다. 가짜 비용 영수증을 만들지 않는다.
- **SELECT**는 `ProductionDiscoveryService.lockForSelection`의 요청·채널 잠금과 최신 후보 재검사, AUTO/MANUAL 선택 조건을 유지한다. `DiscoverySelections`와 `ProductionSelectionService`가 사용하는 선정 지문·배치 버전·요청 스냅샷 및 revision 연결을 그대로 검증한다. AI의 순위·난이도는 참고 입력이며 SELECT나 중복 판단을 대신하지 않는다.
- **RESEARCH → SCRIPT → SCENES**는 선정 후에만 발행한다. `ProductionPlanning`의 후보 ID·DISCOVER 해시·revision 해시·claim·segment 계약을 재사용한다. Claude가 후보·초안과 연출을 제안하고 Codex가 근거·대본·프롬프트를 검수한다. Spring Boot가 버전·근거·규칙·선정 지문을 검증하고 현재 lease에서 채택한다. 미디어 실행, 비용 산정과 발송은 기존 서버 책임이다.
- **별도 시스템 추가 없이 기존 Worker 작업 종류 확장**을 제안한다. 기존 요청별 실행 모드를 동결한다. 구독 Worker와 Gemini LLM API는 명시 선택이며 추가 과금·유료 검색·LLM API로 자동 전환하지 않는다. 검색 접근이 없거나 저장 조건이 불명확하면 권리 확인된 운영자 제공 근거로 제안할 수 있다. 근거 없는 최신 사실·트렌드는 보류한다.

## 버전 있는 파일 계약 제안

| 경계 | 내용 |
|---|---|
| 서버 요청 | 기존 request/step/attempt/epoch 및 SHA-256과 schemaVersion, 채널 ID·config ID, 채널 콘셉트·언어·목표 길이, 최근 소재 식별자·제목/핵심 질문의 제한된 스냅샷과 제외 소재, 제작비 상한(문자열 KRW), 목표 제작량과 후보 수, 평가 규칙 버전·기준. 목표 제작 수량과 후보 수를 각각 표시하고 기존 후보 수 범위 5~10 및 요청당 선정 1건을 유지 |
| Claude 후보 결과 | 기존 title/centralQuestion/rationale/coreTopic/angle/outline/evidencePlan에 훅과 코미디·시각 연출 설명, 난이도 추정, 트렌드 상태를 추가 제안. 후보별 근거 ID·claim·출처 URL·확인 UTC·원문 위치와 관련성, 검색/조회 증거 참조 포함 |
| Codex 검수 | 근거의 일치·반증·불충분 구분, 실화/가정 분리, 후보의 중복 단서, 장면 가능성·위험. 결과에 요청/초안 SHA-256을 연결. 자동 도구 실행이나 SELECT, 비용 변경 권한은 부여하지 않음 |
| 서버 수집 | 파일 크기·타입·필드·버전/해시, 오염된 URL과 candidate 참조를 확인하고 기존 평가를 다시 수행. 확인 가능한 출처와 주장만 검증에 사용. 선정 시점에 최신 카탈로그로 다시 대조 |

검색 결과 목록이나 모델이 적어 낸 URL은 조사 단서로 저장한다. URL이 열렸다는 증거, 조회 시각, 원문 위치·관련성을 대조해야 확인으로 승격한다. 모델의 자기 보고만 있다면 기존 `MODEL_HINT/UNVERIFIED`를 유지한다. 조회 실패·유료벽·신선도 미확인은 후보 보류/근거 부족으로 표기하며 출처·확인 날짜를 꾸미지 않는다. 트렌드는 `VERIFIED / UNVERIFIED / NOT_ASSESSED` 구분과 출처·관측 기간·지역 또는 관심 지표를 요구한다. 검색 노출 순위나 기사 날짜만으로 실제 트렌드를 단정하지 않는다. 출처 본문·검색 결과 저장과 재전송은 해당 권리 범위 내에서 필요한 최소 증거만 남긴다.

## CLI 검색과 이용 조건

| 대상 | 공식 지원과 제한 |
|---|---|
| Claude Code | `claude -p`는 도구 허용 제어를 지원한다. `WebSearch`는 제목·URL 탐색이며 원문 확인은 별도 `WebFetch`다. 검색 한 번이 여러 백엔드 검색을 일으킬 수 있어 검색 횟수·시간·허용 출처를 제한할 필요가 있다. 현 계정·리전 및 구독 포함량에서 두 도구가 동작하는지는 **NOT_VERIFIED**이며, 이전 일회성 대본 시험은 검색 없이 진행했다. [비대화형 실행](https://code.claude.com/docs/en/headless) · [검색 도구 계약](https://code.claude.com/docs/en/tools-reference#websearch-tool-behavior) |
| Codex | 공식 웹 검색은 cached/live 모드와 비활성화를 지원하고 `codex exec --json`에 검색 이벤트가 남는다. cached 검색으로 최신 트렌드나 직접 조회를 입증할 수 없다. 호스팅 검색은 셸 네트워크 제한과 별개이므로 모델 설정과 워크스페이스 제한을 따로 확인한다. 이 계정의 실제 검색·사용량은 미실험이다. [Codex 웹 검색](https://learn.chatgpt.com/docs/web-search?surface=app) |
| 무인 반복 | Claude 개인 약관은 명시 허용 또는 API 키 등에 해당하는 예외를 두고 자동 접근을 제한한다. 제품 내 CLI 실행에는 Anthropic의 별도 조건이 적용될 수 있다. OpenAI도 공식 CLI 자동화 지침과 개인 약관의 자동 추출 제한을 함께 둔다. 현재 사용 형태인 **개인 구독 + 채널별 무인 반복 검색·콘텐츠 제작·결과 자동 수집**에 대한 명확한 허용 근거는 미확인이다. 일회성 검수 성공은 이 범주의 허용 근거가 아니다. [Claude 개인 약관](https://www.anthropic.com/legal/consumer-terms) · [Claude Code 통합 조건](https://code.claude.com/docs/en/legal-and-compliance) · [Codex 비대화형 실행](https://learn.chatgpt.com/docs/non-interactive-mode) · [OpenAI 이용약관](https://openai.com/policies/terms-of-use/) |

## 실행, 격리 및 단계 귀속

- 채널별 스케줄과 시간대, 선행 요청 완료 및 목표 제작량 판정은 Spring Boot가 소유한다. 승인 후에는 기존 요청 큐에 채널·예약 슬롯 기준 중복 방지 키를 발행하는 방식을 검토한다. PC 중단 후 누락 슬롯을 무제한 소급 발행하지 않는다. 채널별 예약/자동 발굴은 기존 V2-07 범위이므로 이번 V2-05 검토에서는 인터페이스 경계만 제안한다.
- 선행 미완료·명시된 구독 한도 초과는 기존 due/park 기반 WAIT. 해제 시각이 없거나 재인증·과금 경로가 불명확하면 운영자 확인까지 보류한다. 일정 트리거나 재부팅 이후에도 기존 operation/시도 상태와 완료 파일부터 확인한다. 기존 lease/fencing으로 낡은 결과 채택을 막고, 원자적 파일 공개와 상위 해시 검증을 적용한다. 스크립트 자동 재호출 및 유료 미디어 API의 자동 재시도는 금지한다.
- 외부 문서·검색 결과는 데이터로만 전달하고, 지시·코드·명령을 실행하지 않는다. 검색 가능 단계와 대본/검수 단계의 도구 권한을 분리한다. 셸·커넥터·불필요한 MCP와 로그인 브라우저는 격리하고, URL 출처 허용 범위·본문 크기·접근 시간 및 외부 전송 데이터를 제한한다. 경로와 권한은 서버/런처가 고정한다. 검증에서는 인젝션 문구, 조작된 URL·날짜, 오래된 후보, 중복 트리거 및 stale 결과 차단을 먼저 확인한다.
- 여러 채널을 지원하되 하루 1~2편, 요청당 3,000원, Gemini TTS + Puck, 자체 자막 제외, 실제 AI 영상 포함과 동적 장면/매체 구성은 유지한다. 트렌드 기능과 무인 실행은 적용 조건 확인과 별도 승인 뒤 활성화한다.

설계 검토만 수행했다. 제품 코드·DB·스케줄러를 변경하거나 모델·유료 검색/API 실험을 실행하지 않았다.
