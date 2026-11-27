# V2-05 저장소 대조 및 공통 계약 제안

**PROPOSED** · 2026-11-27 KST · 기준 `e51e64971a9e16251b38f86e23f7c778e7eb11eb` · [상세 계획](V2-05_IMPLEMENTATION_PLAN.md)

## D01~D13 대조

| 항목 | 현재 계약과 재사용 | V2-05 변경 제안 |
|---|---|---|
| D01 | `ProductionRequestService`가 mediaEnabled로 `MediaProfile.PIPELINE`(v2-media-1)을 선택. `ExecutionMapper`, V13, 비용·계획·자산 매퍼도 literal 사용 | 새 요청만 v2-media-2, 실제 활성화는 서버 설정과 채널 허용을 모두 확인. 공통 버전 판별과 SQL/트리거 허용 범위를 함께 수정. 기존 기본 opt-in과 기존 요청은 현행 동작 유지 |
| D02 | 후보·데이터 보존 제한은 [공급자 조사](06_AI_PROVIDER_AND_PRICING.md)에서 재사용 | [공식 문서 확인 및 스파이크](V2-05_PROVIDER_SPIKE.md) 적용. 공개 요금과 계정별 청구 증거를 별도로 확인 |
| D03 | `Channel.language` BCP 47 검증, 요청 생성 시 discovery와 media profile에 locale snapshot, 선택 서비스에서 revision 언어 전달. `DiscoveryProvider`, `PlanningProvider`, `ResearchEvidenceProvider`, `ScriptReview`, V12 단계 결과 재사용 | IFNOVA 채널 ko-KR 설정. 새 프로필에 모델, voice, 발음·프롬프트 버전 고정. 조사 원문 보존권과 claim 근거 검증, 의미 중복/사실 확인은 사람 인수에 전달. 공급자별 한국어 출력 품질 검증 |
| D04 | IMAGE → TTS 고정. IMAGE 장면별 item과 순서 보장 | IMAGE root에 이미지 및 영상 하위 효과. I2V의 부모 이미지를 먼저 확정. T2V는 부모 없이 시작. 절차적 렌더는 허용 프리셋의 로컬 산출물. 실측 음성 이후 유료 호출 없이 trim/pad 범위에서 맞추고 불일치는 보류 |
| D05 | `ProductionCostService`, `ProductionCostMapper`, `BudgetControlLock`, request limit snapshot, 요청·일·월 한도 및 원장 재사용 | IFNOVA 새 실제 요청은 활성 한도와 3,000원 중 낮은 값. 가격·환율·세금·수수료 최대치와 원통화 증거를 함께 동결. 초과 또는 청구 귀속 불명은 추가 지출 차단 |
| D06 | `ProductionCostPort.PricedRequest`는 fakeScenario, `MediaEffectPort.FakeProvider`는 즉시 proof+payload 반환. 기존 park는 불명 회복용 | 실제 어댑터의 typed submit/poll/download/cancel 및 청구 확인. job ID 기반 대기는 불명 응답과 구별. 같은 효과 재발송 금지 |
| D07 | V9 operation, V12 결과, V13 media item/asset. V13 role/kind/trigger와 mapper가 고정 | 아래 V14 제안. 기존 proof·receipt와 제출 증거의 소유권 유지 |
| D08 | `EffectStages`, `FixtureCatalog`, `Resampler`, `Timeline`, `Alignment`, `SubtitleTrack`, `SubtitleStage`. 현재 낭독과 타이밍은 fixture이며 `SpeechTiming`은 영어용 | 한 장면 한 화자 유지. `ScriptReview`와 `ScenesReview`의 추정 버전을 한국어용으로 제공하고 기존 영어 버전은 보존한다. 실제 PCM 및 정렬 어댑터 추가. Chirp 정렬 검증에 실패하면 장면 경계로 단어 정렬을 추정해 합격시키는 경로 없이 보류 |
| D09 | `RenderStage`, `RenderPlan`, `FilterGraphs`, `MediaTools`, `ProtectedMediaProcessRunner`, `WorkspaceMediaStore`, `LocalMediaAssets`, `MediaCleanup` 재사용. 현재 렌더 입력은 정지 이미지 중심 | 각 장면의 검증된 still/clip을 고정 graph 입력으로 연결. 제안 클립 상한 720×1280·5초·16 MiB·단일 영상 스트림으로 시작하고 실제 protected smoke에서 조정. 정규화 및 전체 decode 비용도 scratch 예약에 포함. Linux 준비 실패 시 기존 차단 |
| D10 | `QcStage`, `MediaFlow.persisted`, `StepPlanner`, `ProductionExecutor` 및 `verifyQc` | 기술 QC 버전 확장. 핵심 움직임·과학 사실·코미디·한국어 발음은 사람 판정. revision/MP4/manifest SHA에 인수 고정. 승인 변경 경계는 V2-06 게시로 인계 |
| D11 | 기존 요청 생성/상세와 `/api/v1/production-requests/{id}/media` 및 `/{assetId}` GET/HEAD/Range. `ProductionRequestStartPage`, `ProductionRequestDetailPage`, `ProductionMediaSection`, `useProductionMedia` | 기존 목록에 모드, 원본 언어, 기술 QC 및 콘텐츠 인수 요약을 선택 필드로 제안. 새 모드 선택은 생성 요청의 선택 필드로 서버 승인 필요. 비용은 문자열. 공통 UI·오류 envelope·권한 유지 |
| D12 | [V2-06 원본 언어·SRT·YouTube 계약](V2-06_YOUTUBE_LOCALIZATION.md) | 한국어 원본, 제목·설명, SRT, 권리 및 승인 해시 인계. 자동 자막 번역·더빙·업로드는 V2-06 |
| D13 | V2-04 공통 SHA 이후 분리 worktree와 독립 리뷰 방식 재사용 | 아래 담당·의존성 적용. 공통 계약에서 제품 구현 시작점 고정 |

기존 결정은 ADR-017/026의 원자 예약, ADR-027/030의 환율·정밀도, ADR-028의 정산과 과금 불명 유지, ADR-061/076의 실제 지출 게이트, ADR-070/071의 잠금·단일 발송, ADR-002의 승인 해시 계약을 적용한다. [DECISIONS](../../DECISIONS.md) v2-media-2, V14, 제출·조회 분리는 승인 대기 제안이다. 이동 순서, 별도 작업 큐, 12번째 단계는 초기 범위에서 제외한다.

## 저장 및 상태 전이

- V14 하나를 제안한다. V13 media item은 IMAGE kind를 유지하고 `<sceneId>:image`, `<sceneId>:video` 같은 서버 키로 분리한다. canonical input JSON에 media type, scene/segment, motion, 가격표, duration, 부모 asset ID/SHA를 고정한다. `production_media_asset.role` 허용값과 kind-role 트리거에 SOURCE_VIDEO/NORMALIZED_VIDEO를 추가한다. 새 역할은 v2-media-2에 한정하며 PROFILE의 기존 불변성, operation/item FK 및 FINAL 역할별 유일성을 유지한다.
- PROFILE JSON을 새 버전으로 확장해 locale/config, 승인 SKU·capability, voice/프롬프트, 가격표 버전, FX 기준과 출력 프로필을 보존한다. 장면 결과는 V12의 버전 있는 JSON, 원본/파생 계보·권리와 probe는 V13 JSON을 사용한다. 가격 레지스트리 관리 UI와 별도 장면 테이블은 초기 범위에서 제외한다.
- V9 `provider_operation`에는 결과 receipt 외에 진행 중 job ID가 없다. 동일 테이블에 nullable `provider_namespace VARCHAR(96)`, `provider_job_id VARCHAR(255)`(binary collation), `submitted_at DATETIME(6)`, `submission_json JSON`, `submission_sha256 CHAR(64)`, `next_poll_at DATETIME(6)`를 제안한다. namespace에는 승인된 공급자/계정/endpoint 스코프를 사용한다. UNIQUE(namespace, job ID), INDEX(status,next_poll_at,id), 제출 필드 동시 존재 CHECK 및 최초 기록 이후 불변 트리거를 둔다. job ID가 없는 기존 행은 그대로 유지한다. 원본 청구 통화·금액·invoice reference는 기존 확정 proof의 버전 필드로 보존한다.
- 상태는 RESERVED → DISPATCHED → SETTLED. job ID가 확인된 실행 대기는 DISPATCHED+job ID+next_poll_at. 제출 응답이 불명하고 식별자가 없으면 UNKNOWN, 예약을 유지한다. 실행 증거가 있으면 item PROVEN → FINALIZED → APPLIED. 취소·기간 종료된 RESERVED만 NOT_EXECUTED와 예약 반환 가능. 외부 작업 취소 확인만으로 무과금 처리하지 않는다. 운영 증거가 서로 다르면 자산 채택과 신규 발송을 막고 조사한다.
- 환율은 예약 시 승인된 상한 환율과 수수료/세금으로 보수적 원화 올림. ADR-027/030에 따라 기본 정산은 예약의 유효 환율을 사용하고 실제 결제 환율·부대비용 차이는 증빙과 별도 조정 원장 이벤트로 기록한다. 예약은 CEILING(6), 정산은 HALF_UP(6) 기준을 유지한다. 청구서 지연 또는 항목 귀속 불가 시 최대 예약을 유지하고 운영 조사로 이관한다. 최대액 초과 청구는 기존 초과/지출 차단 정책으로 반영하고 추가 발송을 막는다.
- V14 검증은 신규 격리 DB 설치와 V13 데이터 보존 업그레이드 양쪽에서 수행한다. 기존·신규 파이프라인 교차 삽입, job ID 덮어쓰기, 부모·operation 교차 귀속 거부를 시험한다. 적용된 기존 파일 수정은 금지하고 롤백은 신규 요청 비활성화와 기존 호환 코드 유지로 계획한다.

## 트랜잭션과 어댑터 경계

1. 기존 비용 잠금과 요청 fencing 아래 canonical hash·가격·효과 키를 검증하고 예약과 item 바인딩을 같이 커밋한다. 이어 별도 permit 트랜잭션에서 현재 lease owner/epoch, 취소, 기간, 지출 차단을 재확인하고 RESERVED→DISPATCHED CAS. 이 성공만 submit 권한을 부여한다.
2. 트랜잭션 밖에서 `submit(frozenInput)` 1회. SDK·HTTP 쓰기 자동 재시도를 비활성화한다. `Accepted(jobId, evidence)`, `Completed(proof, payloadRefs)`, `DefinitiveFailure(evidence)`, `Unknown`을 구분한다. 접수 증거는 해당 permit/operation에 귀속해 즉시 커밋한다. 늦은 증거 수용은 해당 operation의 동일 귀속·불변 조건에서만 허용하며 다음 단계 실행 권한은 부여하지 않는다.
3. `poll(jobRef)`, `download(jobRef, artifactRef)`, `cancel(jobRef)`, `reconcile(readOnlyRef)`는 capability 기반. job ID가 있으면 현재 owner가 동일 작업을 조회한다. 제한 timeout과 backoff 및 Retry-After를 next_poll_at에 반영한 뒤 lease를 반환한다. 기존 claim SQL과 planner를 due recovery로 확장하고 대기·조회는 stage attempt를 소모하지 않는다. 미디어 실행 불가 상태에서도 비용 정산용 복구는 진입 가능하다. 장시간 DB 잠금과 poll 대기 중 scratch 예약을 피한다.
4. B01은 바인딩이 있으면 입력·키·FFmpeg·부모 파일 검사 전에 기존 발송 금지 recover를 실행한다. 유효한 미발송만 입력 검증 후 같은 operation으로 permit 재제시. 접수 직후 job ID 커밋 전 장애에 대해 조회 근거가 없으면 UNKNOWN과 예약을 유지하며 신규 세대·대체 SKU를 잠근다.
5. 완료 시 확정 실행 proof를 파일과 별도 커밋. 수신 byte/time/호스트 제한과 V2-04의 고유 attempt, 해시 검증, 원자 move를 적용한다. 현재 epoch에서 FINAL과 RESULT_ADOPTED 증거를 함께 커밋하고, 기존 settle key `production:settle:<op>`로 정산한 뒤 receipt 적용 및 APPLIED를 함께 커밋한다. 취소/stale worker는 채택·공개를 할 수 없으며 귀속 비용 증거만 보존한다. 임시 파일 복구는 기존 24시간 한계를 적용한다.
6. B02는 로컬 단계 APPLIED 판정 후 새 시도 없이 recover. QC 3차 중단은 MP4/QC_REPORT/MANIFEST 해시 재확인 후 FINAL+step 성공을 같은 트랜잭션에서 처리한다. 손상은 기존 무결성 차단. 새 자산과 프로필 모두에 이 경계를 적용한다.

재생성은 신규 세대 비용과 재검수까지 동반한다. 현재 OpenAPI에 범용 단계 재생성이나 공급자 수동 해결 API가 없다. 최초 인수는 예산 내 최초 계획과 검증된 동작으로 수행한다. 재생성이 필요하면 같은 요청·잔여 한도 내 명시 승인, 세대별 참조 선택과 이전 승인 무효화를 공통 계약에서 먼저 확정한다. 기존 요청 예산을 우회하는 새 요청 자동 발행은 금지한다. 콘텐츠 인수 UI를 제공할 경우 기술 QC와 분리하고, 기존 소유권·CSRF 하에서 revision/해시 및 판정자를 영속화한다. 최초 인수는 증거와 해시를 결과 보고서에 기록하는 것으로 시작할 수 있다.

## 구현 의존성과 검증

- **Codex 공통** · 승인 설계 이후 공통 포트, DB/API, 실발송 허가, 예약/정산, 비동기 복구 및 실행 안전성·공통 회귀를 소유한다. V14·OpenAPI·생성 타입·공통 파일은 이 브랜치에서 조율한다.
- **Claude 제품** · 공통 SHA에서 분기해 어댑터, 한국어 대본/TTS, 연출, 혼합 렌더, 제품 QC/UI와 제품 테스트를 소유한다. 공통 계약이 고정되면 포트 mock으로 어댑터·렌더·UI를 병렬 검증할 수 있다. 보호 실행기 및 실제 DB 경계 테스트는 공통 변경과 결합해야 통과 판정할 수 있다.
- **Codex 통합** · 별도 worktree에서 양쪽 브랜치 결합 및 검증. 독립 Codex 리뷰는 구현/통합과 다른 세션에서 지정 HEAD를 읽기 전용 확인한다. 최신 CI와 사용자 인수까지 완료 후 종료를 승인받는다.
- T01~T12에 더해 B01 미디어 입력 누락/손상×유효/만료/취소, job ID 커밋 전후 JVM 강제 종료, 중복·불일치 응답과 기간 교차, B02 마지막 QC 결과 손상 및 이전 worker 확정 거부를 추가한다. paid adapter 모의 HTTP와 격리 MariaDB로 호출 횟수·원장 수·파일 해시를 검증하고 Windows 실제 보호 실행기와 Linux 차단을 확인한다.
