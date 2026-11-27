# V2-05 구독 기반 외부 AI Worker 설계 검토

**PROPOSED** · 2026-11-27 KST · 기준 `v2/05-design` `45398488236427d84c49397a4ba235c1babe045b` · 설계 기준 `5d6fa55ffafa09dc29b33950bdffe7c6ac64700d`

## 결론

하루 1~2편의 단일 운영자 환경에서는 Windows 파일 Worker를 **별도 검증 작업**으로 분리한다. 제품에 채택할 경우 기존 DISCOVER/RESEARCH/SCRIPT/SCENES의 선택 가능한 실행 백엔드로 통합한다. DB 큐와 단계 검증은 기존 서버가 계속 소유한다. 구독 방식의 실제 계정·약관·한도 처리에 대한 근거와 장애 회귀가 확보되기 전에는 무인 실행을 활성화하지 않는다. 일반 CLI 사용과 자동화의 확인 범위는 아래와 같다.

| 대상 | 공식 문서로 확인 | 채택 전 확인 |
|---|---|---|
| Codex | `codex exec`는 비대화형·스키마 출력 지원, 저장된 CLI 인증 재사용. 문서는 신뢰된 환경에서 ChatGPT 관리 인증 자동화도 설명한다. 개인 약관은 자동 추출 제한을 둔다. [비대화형 문서](https://learn.chatgpt.com/docs/non-interactive-mode) · [구독 인증](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan) · [개인 약관](https://openai.com/policies/terms-of-use/) | 공식 CLI가 지원하는 범위에서 사용자 본인의 로컬 작업과 출력 수집이 해당 플랜/워크스페이스에서 허용되는지 확인. 소비자 웹 UI 스크래핑, 토큰 중개, 다른 사용자에게 구독 재판매는 범위 밖 |
| Claude Code | `claude -p`는 비대화형 실행을 지원하고, Claude 플랜 로그인 시 플랜 한도를 사용한다. `--bare`는 구독 로그인과 호환되지 않는다. Consumer Terms는 명시적 허용 또는 API 키의 예외를 둔다. 제품/서비스에서 Claude Code를 실행하는 경우 Commercial Terms 및 별도 조건이 적용될 수 있다. [비대화형 문서](https://code.claude.com/docs/en/headless) · [플랜 과금 FAQ](https://support.claude.com/en/articles/17154008-monthly-api-credits-for-max-ultra-and-team-plans) · [Claude Code 이용 조건](https://code.claude.com/docs/en/legal-and-compliance) · [개인 약관](https://www.anthropic.com/legal/consumer-terms) | 사용자 본인의 로컬 공식 CLI 로그인은 기술 검증 후보. Spring Boot가 반복적으로 생산을 요청하는 이 구성의 무인 운영 허용 범위는 **NOT_VERIFIED**. 계약 확인 전 제품 기본값이나 배포 구성에 넣지 않음 |

요청은 로컬 파일에 보관하지만 모델 처리는 외부 서비스에 전송된다. 전용 Windows 프로필에서 운영자가 공식 로그인하고 Spring Boot는 계정 토큰을 읽거나 전달하지 않는다. API 키·대체 클라우드 인증·결제 프롬프트·자동 업그레이드/충전의 경로를 제거한다. 구독 크레딧이 자동으로 소비되는 설정까지 확인해야 한다. 인증/과금 경로가 불명확하면 실행을 보류한다. 구독 기본료는 운영비로 표시하고 유료 미디어 효과는 기존 3,000원 한도와 비용 원장에 귀속한다. 구독 초과분이나 API 대체는 자동 허용하지 않는다. [OpenAI 크레딧](https://help.openai.com/en/articles/12642688-using-credits-for-flexible-usage-in-chatgpt-personal-plans) · [Claude 사용 크레딧](https://support.claude.com/en/articles/12429409-manage-usage-credits-for-paid-claude-plans)

## 저장소 대조와 변경 범위

- `ProductionExecutor`의 DISCOVER와 RESEARCH/SCRIPT/SCENES는 기존 비용 영수증·검증·적용 흐름에 결합되어 있다. `DiscoveryProvider`, `ResearchEvidenceProvider`, `PlanningProvider`의 입력과 결과 계약을 재사용하되 파일 제출/수집에는 비동기 실행 경계를 추가한다. 기존 유료 포트에 동기 래퍼를 끼우거나 구독 작업에 가짜 비용 영수증을 생성하면 현재 복구 의미가 달라진다. 요청 시작 시 `LLM_API` 또는 `SUBSCRIPTION_WORKER`를 동결하고 실제 활성화는 별도 설정으로 제어한다.
- 통짜 결과를 바로 미디어로 넘기는 구성은 DISCOVER 이후 SELECT와 선정 지문 검증을 건너뛴다. 최초 패킷은 후보 발굴을 처리하고 서버가 기존 SELECT 경계를 거친 후 선정 정보와 근거를 후속 패킷에 추가한다. 구현 최소안은 해당 단계별 Claude 초안 → Codex 검수/수정 → 서버 검증·채택이다. 이후 묶음 처리가 필요하면 기존 단계별 결과의 의존 해시와 검증을 유지한다.
- Codex 결과는 수정본·수정 근거·보류 항목을 반환한다. 선정 후보·revision·claim·segment 연결은 서버 검증 기준이다. 출처 URL과 근거 식별자를 제출하고 원문을 확보하지 못하면 미검증으로 표시한다. Codex 평가는 최종 영상의 기술 QC와 사람 인수를 대체하지 않는다.
- 승인 채택 시 코드 범위는 실행 백엔드 선택, DB에 연결되는 작업/시도 상태와 due 처리, 제한된 CLI 런처 및 파일 수집기, 기존 검증 적용 경로, 요청 상세의 대기 사유, 장애 회귀에 한정한다. Worker 검증을 독립 작업으로 두어 V2-05의 유료 API 선택과 미디어 경로를 유지한다.
- 미디어 변경은 이번 Worker 검토의 산출물과 분리한다. 이번에 제시한 결정은 **Gemini TTS + Puck, 자체 자막 제외, Gemini/fal 이미지, fal 동영상, 동적 구성, BGM/SFX, 40~60초 한국어 과학 코미디, 3,000원 상한**이다. 기준 설계의 Chirp/ElevenLabs 후보 및 ASS/SRT·번인 출력과 차이가 있다. 후속 설계 반영 시 v2-media-2에서 자체 자막 산출·번인·검수를 제외하는 방식과 기존 SUBTITLE 단계의 호환 처리를 정한다. TTS 실측 시간·내부 정렬, TTS → IMAGE, 기존 v2-media-1 자산과 검수는 유지한다.

## 작업 소유권과 복구 제안

1. **DB가 권위 상태.** Spring Boot는 기존 요청/step 및 lease fencing으로 영속 발행 의도를 먼저 기록하고 `requestScript`에 재생 가능한 입력을 생성한다. `jobId`, 역할, request/step/선정 revision, 입력 SHA-256, schemaVersion, 시도·epoch, deadline을 포함한다. Windows 작업 스케줄러는 전용 런처를 깨운다. 런처는 서버의 로컬 제한 인터페이스를 통해 단일 실행 권한을 받아 CLI를 시작한다. CLI에 DB 자격증명은 주지 않는다. 대기 동안 요청 lease를 반환하고 작업 attempt의 고정 토큰과 적용 시 현재 요청 epoch를 각각 검증한다.
2. **파일은 운반 수단.** Claude 초안은 `draftScript`, Codex 검수 출력은 `resultScript`에 둔다. 경로·파일명은 job/역할/attempt로 서버와 런처가 구성한다. 모델은 제한된 JSON 결과만 반환하며 런처가 안전한 디렉터리에서 임시 파일 쓰기·flush 후 같은 볼륨 내 atomic rename으로 공개한다. 초안/결과에는 상위 입력·초안 해시와 schemaVersion을 넣고 소비자는 실제 바이트 SHA-256·크기·스키마·현재 job/attempt·선정 지문 및 기존 비즈니스 규칙을 확인한다. 동일 결과는 재수집해도 한 번만 적용하고 상충 결과는 격리한다.
3. **WAIT와 복구.** 확인된 구독 한도는 논리 `WAIT`로 표시한다. 기존 DB due/park 구조를 재사용해 공식 reset 시간이 있으면 그 이후에만 재확인하고, 시간 미확인·재인증·과금 경로 불명은 운영자 재개로 남긴다. 대기 확인은 제작 시도 횟수에 합산하지 않는다. 타임아웃·프로세스 중단·재부팅 후에는 영속 intent와 커밋된 파일을 먼저 대조한다. 완료 파일이 있으면 재실행 없이 수집한다. 이전 프로세스 트리 종료가 확인되고 현재 attempt가 무효화된 후에만 제한된 재실행을 허용한다. 외부 구독 호출이 실제 수행됐지만 결과가 유실될 수 있으므로 공급자 측 exactly-once는 보장할 수 없다. 불명 과금에는 자동 재발송을 적용하지 않는다.
4. **격리.** 전용 비관리자 Windows 계정, NTFS ACL로 입력은 읽기 전용, 출력은 지정 작업 경로만 허용한다. 프로필별 인증 저장소는 앱·다른 작업 계정에서 접근을 막는다. 신뢰된 빈 작업 폴더와 고정 CLI 버전·argv, 관리된 설정으로 프로젝트 hooks, MCP, shell/edit 도구와 불필요한 커넥터를 막는다. Claude 기본 `-p`는 프로젝트 hooks/MCP를 로드할 수 있으므로 설정 격리가 필수다. 경로 역참조·junction·심볼릭 링크·공유/동기화 경로, 과대 파일, 로그의 토큰·서명 URL을 차단한다. 런처는 Windows Job Object 등으로 프로세스 트리를 소유하고 종료를 확인한다. 작업 스케줄러의 중복 금지 설정은 DB의 claim/CAS를 보완한다. [Claude 비대화형 설정](https://code.claude.com/docs/en/headless)

## S1 대체 범위와 검증

구독 경로가 허용되면 동일한 한국어 소재 2개, 동일 근거·평가표로 **Claude 초안과 Codex 검수 후 결과**를 각각 평가해 운영 경로의 대본 품질 시험으로 S1을 대체할 수 있다. 기존 S1의 세 API 모델 비교, API별 토큰·단가·응답 재현성에 관한 결론은 얻을 수 없다. API 경로의 공급자 선택이 필요한 시점에만 그 비교를 별도 승인 범위로 남긴다. 기존 S1 호출표는 이번 검토에서 변경하지 않는다.

검증 순서 제안은 무과금 파일 왕복과 가짜 CLI로 중복 트리거, 열려 있는 파일, 손상·stale 결과, quota/auth 분류, 재부팅 및 프로세스 종료 경계를 확인한 뒤, 사용자 계정·약관 확인과 별도 승인 범위에서 실제 CLI로 2개 표본을 평가하는 것이다. 대본/검수 품질 외에 작업별 사용량과 대기 시간, 하루 1~2편을 운영할 때 기존 구독 사용과의 경합을 기록한다. 공개 문서나 호출 표본만으로 장기 처리량을 보장하지 않는다.
