# V2-05 저장소 대조 및 공통 계약 제안

**PROPOSED** · 2026-11-27 KST · 기준 `e51e64971a9e16251b38f86e23f7c778e7eb11eb` · [상세 계획](V2-05_IMPLEMENTATION_PLAN.md)

## D01~D13 대조

| 항목 | 현재 계약과 재사용 | V2-05 변경 제안 |
|---|---|---|
| D01 | `ProductionRequestService`가 mediaEnabled로 `MediaProfile.PIPELINE`(v2-media-1)을 선택. `ExecutionMapper`, V13, 비용·계획·자산 매퍼도 literal 사용 | 새 요청만 v2-media-2, 실제 활성화는 서버 설정과 채널 허용을 모두 확인. 공통 버전 판별과 SQL/트리거 허용 범위를 함께 수정. 기존 기본 opt-in과 기존 요청은 현행 동작 유지 |
| D02 | 후보·데이터 보존 제한은 [공급자 조사](06_AI_PROVIDER_AND_PRICING.md)에서 재사용 | [공식 문서 확인 및 스파이크](V2-05_PROVIDER_SPIKE.md) 적용. 공개 요금과 계정별 청구 증거를 별도로 확인 |
| D03 | `Channel.language` BCP 47 검증, 요청 생성 시 discovery와 media profile에 locale snapshot, 선택 서비스에서 revision 언어 전달. `DiscoveryProvider`, `PlanningProvider`, `ResearchEvidenceProvider`, `ScriptReview`, V12 단계 결과 재사용 | IFNOVA 채널 ko-KR 설정. 새 프로필에 모델, voice, 발음·프롬프트 버전 고정. 조사 원문 보존권과 claim 근거 검증, 의미 중복/사실 확인은 사람 인수에 전달. 공급자별 한국어 출력 품질 검증 |
| D04 | IMAGE → TTS 고정. IMAGE 장면별 item과 순서 보장 | SCENES 후보와 기존 미디어 계획에서 장면별 적합성과 가격을 검증. IMAGE root에 이미지 및 영상 하위 효과. I2V의 부모 이미지를 먼저 확정. T2V는 부모 없이 시작. 절차적 렌더는 허용 프리셋의 로컬 산출물. v2-media-2에서 TTS 실측 후 IMAGE 계획 확정·발송, 불일치는 발송 전에 보류 |
| D05 | `ProductionCostService`, `ProductionCostMapper`, `BudgetControlLock`, request limit snapshot, 요청·일·월 한도 및 원장 재사용 | IFNOVA 새 실제 요청은 활성 한도와 3,000원 중 낮은 값. 가격·환율·세금·수수료 최대치와 원통화 증거를 함께 동결. 초과 또는 청구 귀속 불명은 추가 지출 차단 |
| D06 | `ProductionCostPort.PricedRequest`는 fakeScenario, `MediaEffectPort.FakeProvider`는 즉시 proof+payload 반환. 기존 park는 불명 회복용 | 실제 어댑터의 typed submit/poll/download/cancel 및 청구 확인. job ID 기반 대기는 불명 응답과 구별. 같은 효과 재발송 금지 |
| D07 | V9 operation, V12 결과, V13 media item/asset. V13 role/kind/trigger와 mapper가 고정 | 아래 V14 제안. 기존 proof·receipt와 제출 증거의 소유권 유지 |
| D08 | `EffectStages`, `FixtureCatalog`, `Resampler`, `Timeline`, `Alignment`, `SubtitleTrack`, `SubtitleStage`. 현재 낭독과 타이밍은 fixture이며 `SpeechTiming`은 영어용 | 한 장면 한 화자 유지. `ScriptReview`와 `ScenesReview`의 추정 버전을 한국어용으로 제공하고 기존 영어 버전은 보존한다. 실제 PCM 및 정렬 어댑터 추가. Chirp 정렬 검증에 실패하면 장면 경계로 단어 정렬을 추정해 합격시키는 경로 없이 보류 |
| D09 | `RenderStage`, `RenderPlan`, `FilterGraphs`, `MediaTools`, `ProtectedMediaProcessRunner`, `WorkspaceMediaStore`, `LocalMediaAssets`, `MediaCleanup` 재사용. 현재 렌더 입력은 정지 이미지 중심 | 각 장면의 검증된 still/clip을 고정 graph 입력으로 연결. 720×1280·5초·16 MiB는 초기 시험 입력. 운영 SKU별 허용 길이·해상도·크기·스트림 제한은 capability·최대 가격·보호 실행 검증으로 고정. 정규화 및 전체 decode 비용도 scratch 예약에 포함. Linux 준비 실패 시 기존 차단 |
| D10 | `QcStage`, `MediaFlow.persisted`, `StepPlanner`, `ProductionExecutor` 및 `verifyQc` | 기술 QC 버전 확장. 핵심 움직임·과학 사실·코미디·한국어 발음은 사람 판정. revision/MP4/manifest SHA에 인수 고정. 승인 변경 경계는 V2-06 게시로 인계 |
| D11 | 기존 요청 생성/상세와 `/api/v1/production-requests/{id}/media` 및 `/{assetId}` GET/HEAD/Range. `ProductionRequestStartPage`, `ProductionRequestDetailPage`, `ProductionMediaSection`, `useProductionMedia` | 기존 목록에 모드, 원본 언어, 기술 QC 및 콘텐츠 인수 요약을 선택 필드로 제안. 새 모드 선택은 생성 요청의 선택 필드로 서버 승인 필요. 비용은 문자열. 공통 UI·오류 envelope·권한 유지 |
| D12 | [V2-06 원본 언어·SRT·YouTube 계약](V2-06_YOUTUBE_LOCALIZATION.md) | 한국어 원본, 제목·설명, SRT, 권리 및 승인 해시 인계. 자동 자막 번역·더빙·업로드는 V2-06 |
| D13 | V2-04 공통 SHA 이후 분리 worktree와 독립 리뷰 방식 재사용 | 아래 담당·의존성 적용. 공통 계약에서 제품 구현 시작점 고정 |

기존 결정은 ADR-017/026의 원자 예약, ADR-027/030의 환율·정밀도, ADR-028의 정산과 과금 불명 유지, ADR-061/076의 실제 지출 게이트, ADR-070/071의 잠금·단일 발송, ADR-002의 승인 해시 계약을 적용한다. [DECISIONS](../../DECISIONS.md) v2-media-2, V14, 제출·조회 분리는 승인 대기 제안이다. 실행 순서는 프로필별로 정의한다. 별도 작업 큐나 추가 단계는 초기안에 필요하지 않다.

## SCENES와 발송 전 계획 검증

- V12 SCENES JSON에 장면 목적·서사 중요도·길이·피사체 운동·시각 개그/반전 의도와 미디어/모델 후보·저비용 대안을 추가한다. `ScenesReview`의 순서·coverage 검증을 재사용하고 서버 가격표로 후보별 예상액과 최대 청구액을 결정한다. AI 제공 금액과 예산 변경 요청은 발송 입력으로 받아들이지 않는다.
- 후보의 청구 최소 단위, 공급자 생성 길이/해상도, 시작 이미지 등 선행 비용, 환율, 실패·취소·재생성 노출액을 반영한다. 현재 원장의 인식액에 미인식 활성 예약 및 미확정 노출액을 중복 없이 더하고 남은 계획을 평가한다. 청구액을 입증할 수 없는 후보는 제외하고 기존 불명 operation은 차단 상태를 유지한다.
- 적합한 우선안을 먼저 평가하고 예산 부족 시 서사상 덜 중요한 미발송 장면의 대안을 먼저 검토한다. 핵심 사건·과학 사실·운동 요구·개그와 타임라인 coverage를 유지하는 완성안만 채택한다. 적합안이 없으면 기존 요청 상세에 보류 사유를 전달한다. 장면 수·길이·미디어 비율·공급자 배정에 고정 목표를 두지 않는다.
- 발송 전 최신 예산·lease를 기존 예약/permit에서 재검증한다. 이미 바인딩된 효과는 기존 복구·정산을 먼저 처리한다. 불변 입력을 새 계획으로 덮어쓰는 대신 미발송 후보를 비교하고 기존 계약에서 새 입력·세대를 허용할 때만 채택한다. 기존 SCENES 및 미디어 실행 흐름 안에서 처리한다.

## R01. TTS 우선 실행

`ProductionRequestService`는 `ProductionStage.values()`로 step을 만들고 `StepPlanner.requireFixedOrder`도 enum 순서를 전제한다. v2-media-2에서 요청의 불변 pipeline version에 따라 두 단계의 순서만 바꾸고 생성·실행 snapshot·planner 검증에 같은 순서 정의를 적용한다. 현재 ExecutionMapper는 step을 ID순으로 읽으므로 생성 시 삽입 순서도 이 정의를 따른다. v2-media-1과 이전 프로필은 기존 순서다. PROFILE의 IMAGE step 귀속은 보존할 수 있으며 이는 TTS 선행을 막는 의존성이 되지 않는다. TTS 입력은 SCENES의 segment·낭독에서 읽고 이미지 자산 의존성을 제거한다. TTS 완료에서 PCM 샘플 수·정렬·해시를 복구 가능한 결과로 확정한다. IMAGE의 첫 비용 예약 전에 실측 길이·coverage·렌더 여유와 잔여 예산을 검증해 최종 계획을 함께 동결한다. 재시작은 같은 TTS 결과와 계획을 재사용한다.

대안인 IMAGE 내부 선행 TTS는 기존 TTS step 귀속·포트의 operation 소유권과 복구를 우회하거나 중복시킨다. SCENES 내부 TTS도 계획 효과 정산과 media asset 귀속이 섞인다. 별도 타임라인 단계는 독립 상태가 필요할 때 검토할 수 있지만, 초기에는 TTS의 영속 결과와 IMAGE 진입 계획 확정 경계로 복구가 가능하다. 두 프로필 순서, TTS 이후 재시작, 길이 초과·정렬 실패에서 IMAGE 발송 0, 취소·stale lease, B01/B02를 회귀 검증한다.

## R02. UNKNOWN 운영 복구

- 권한이 제한된 관리 CLI는 애플리케이션 서비스에 operation ID, 기대 상태/증거 해시, 결론, 증거 참조, 사유, 운영자 식별자와 idempotency key를 전달한다. DB 직접 변경은 허용하지 않는다. 계정·SKU·요청 지문/상관 ID·발송 시각·공급자 조회 및 청구 기록을 대조한다. 조회 대상이 유일하게 귀속될 때만 진행한다.
- 일치하는 job ID는 기존 operation에 한 번만 연결한다. 충돌하는 namespace/job ID나 기존 증거의 덮어쓰기는 거부한다. 확인된 작업은 조회·다운로드·정산 전용으로 재개하고 신규 submit 권한을 부여하지 않는다. 최종 청구가 확인되면 기존 증거·영수증 경로를 사용한다. 청구만 확인되고 결과가 복구 불가하면 EXECUTED_FAILED로 실제 비용을 정산한다.
- 공급자가 요청 미접수와 청구 없음 모두를 해당 요청에 대해 입증한 경우에만 운영자 NOT_EXECUTED를 허용한다. 조회 목록에 없음, 취소 응답, 경과 시간만으로는 부족하다. 기존 reserve/permit과 분리된 증거 승인 분기로 0원 종료하며 기존 operation과 효과 키에 재발송 권한을 부여하지 않는다. 미입증 건은 UNKNOWN 및 예약 상태를 유지한다.
- 기존 비용 잠금 순서와 operation 상태 CAS로 worker의 늦은 증거와 경합을 제어한다. 운영자·시각·결론·전후 상태·증거 해시·receipt ID를 감사에 남긴다. 같은 키·같은 의도는 기존 결과 반환, 다른 의도는 충돌 처리. `production:settle:<op>` 단일 정산, 기존 lease의 채택 펜싱, 비밀정보 제거를 유지한다. 중복 CLI, 지연 응답, 상충 증거, 권한 거부를 모의 격리 DB에서 검증한다.

## 저장 및 상태 전이

- V14 하나를 제안한다. V13 media item은 IMAGE kind를 유지하고 `<sceneId>:image`, `<sceneId>:video` 같은 서버 키로 분리한다. canonical input JSON에 media type, scene/segment, motion, 가격표, duration, 부모 asset ID/SHA를 고정한다. `production_media_asset.role` 허용값과 kind-role 트리거에 SOURCE_VIDEO/NORMALIZED_VIDEO를 추가한다. 새 역할은 v2-media-2에 한정하며 PROFILE의 기존 불변성, operation/item FK 및 FINAL 역할별 유일성을 유지한다.
- PROFILE JSON을 새 버전으로 확장해 locale/config, 승인 SKU·capability, voice/프롬프트, 가격표 버전, FX 기준과 출력 프로필을 보존한다. 장면 결과는 V12의 버전 있는 JSON, 원본/파생 계보·권리와 probe는 V13 JSON을 사용한다. 가격 레지스트리 관리 UI와 별도 장면 테이블은 초기 범위에서 제외한다.
- V9 `provider_operation`에는 결과 receipt 외에 진행 중 job ID가 없다. 동일 테이블에 nullable `provider_namespace VARCHAR(96)`, `provider_job_id VARCHAR(255)`(binary collation), `submitted_at DATETIME(6)`, `submission_json JSON`, `submission_sha256 CHAR(64)`, `next_poll_at DATETIME(6)`를 제안한다. namespace에는 승인된 공급자/계정/endpoint 스코프를 사용한다. UNIQUE(namespace, job ID), INDEX(status,next_poll_at,id), 제출 필드 동시 존재 CHECK 및 최초 기록 이후 불변 트리거를 둔다. job ID가 없는 기존 행은 그대로 유지한다. 원본 청구 통화·금액·invoice reference는 기존 확정 proof의 버전 필드로 보존한다.
- 상태는 RESERVED → DISPATCHED → SETTLED. job ID가 확인된 실행 대기는 DISPATCHED+job ID+next_poll_at. 제출 응답이 불명하고 식별자가 없으면 UNKNOWN, 예약을 유지한다. 실행 증거가 있으면 item PROVEN → FINALIZED → APPLIED. 취소·기간 종료된 RESERVED는 기존 NOT_EXECUTED로 처리한다. UNKNOWN에 대한 예외적 NOT_EXECUTED는 아래 운영 증거 계약을 통과해야 한다. 외부 작업 취소 확인만으로 무과금 처리하지 않는다. 운영 증거가 서로 다르면 자산 채택과 신규 발송을 막고 조사한다.
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

## 최종 설계 반영 · 오디오 연출과 동작 동기화

**PROPOSED · 2026-11-28 KST.** 사용자 청취 결과에 따라 내레이션 중심의 믹스에 장면별 오디오 연출을 포함한다. IFNOVA는 Gemini TTS Puck, FLUX Schnell, Wan I2V Flash 우선, 자체 자막 제외, 실제 AI 영상과 동적 장면 구성, 하루 1~2편의 40~60초 한국어 과학 코미디를 유지한다. 다중 채널·구독 Worker 계약과 영상당 3,000원 상한을 유지한다.

### 구현 근거와 재사용

- V2-04의 `RenderPlan`은 영상당 BGM ID 하나와 HOOK/TRANSITION/PUNCH의 샘플 오프셋·gain을 지원한다. `FilterGraphs`의 48 kHz 타임라인·샘플 지연·BGM fade·내레이션 sidechain ducking·`amix`, `RenderStage`의 2-pass loudnorm, `QcStage`의 디코딩 AAC 검사, 보호된 FFmpeg 실행기, `FixtureCatalog.Rights`와 `MetadataStage`의 출처·해시·라이선스 기록을 재사용한다. 장면별 BGM 선택, crossfade, Foley/ambience와 실측 영상 동기화는 V2-05 확장 범위다.
- S2에서 Puck 전체 합성은 47.07초였다. 장면별 합성은 4.4 LU 편차와 한 장면의 +0.1 dBTP를 보였으므로 전체 합성을 우선하되 완전성·실측 길이를 검증하고 최종 믹스에서 정규화·피크를 측정한다. 무음은 휴지 후보이며 의미상 장면 경계나 동작 프레임의 증명으로 사용하지 않는다. S4에서 Wan I2V 두 표본은 무음이었고 T2V 표본에는 AAC가 포함됐다. I2V 커피 이탈과 동시 낙하도 의도와 다른 동작·시점이 관측됐다. 이를 원본 오디오 제거와 생성 후 이벤트 재정렬의 근거로 사용한다. 공식 청구 확인 및 최초 혼합 영상 인수는 이 관측과 별개다. [S2~S4 보고서](https://github.com/Hjinsu/ai-studio/blob/d4d42e154e119ba0e732a6b3947edd6d1636b76c/docs/reports/V2-05_SPIKE_S2-S4_REPORT.md)

### SCENES 계획과 확정 큐

- 기존 구독 Worker의 Claude 초안은 SCRIPT에 농담·반전·침묵의 의도를 표시하고 SCENES의 장면별 `soundEvents`에 구조화한다. 각 이벤트는 `eventId`, `sceneId`, `category`(BGM, FOLEY, SFX, AMBIENCE, COMEDY_SFX, TRANSITION_SFX, SILENCE), `subjectAction`, `mood`, `searchTags`, `anchor`, `estimatedOffsetMs`, 필요 시 `durationMs`, `suggestedGainDb`, `priority`, `portrayal`(현실음/연출음)을 포함한다. 앵커는 장면 시작/종료, 내레이션 segment, 전환, 또는 화면 사건(예: 컵 접촉)이다. BGM 이벤트로 분위기와 전환을, SILENCE에는 제외할 레이어와 지속 범위를 제안한다. 일반 연출 침묵은 음악·효과를 멈추고 낭독을 보존한다. 전체 무음이 필요한 경우에는 대본에 계획된 휴지 안에서만 사용한다.
- Codex는 대사 가독성, 코미디·반전의 타이밍, 소리의 과학적 타당성, 과잉 효과·겹침, 정적 연출과 동작 앵커의 적합성을 검수한다. 진공에서 실제 환경음이 들리는 것처럼 표현하지 않는다. 필요한 경우 우주 장면의 효과음을 비사실 코미디 연출로 태그하고 혼동 가능성을 사용자 검수에 올린다.
- AI Studio가 enum·수치 범위·참조와 후보 권리를 검증하고 음원을 선택한다. 채널별 설정에 스타일, 분위기/태그 우선순위, 레이어별 기본 gain·허용 범위, 제외 태그를 버전 관리해 요청에 동결한다. AI가 지정한 gain은 제안이며 앱이 제한한다. AI는 로컬 파일 경로, 음원 ID 확정, FFmpeg 명령, 음원 구매를 지시하지 않는다.
- TTS → IMAGE/영상 → RENDER의 순서를 유지한다. RENDER 준비에서 확정된 clip의 PTS와 실제 프레임을 보호 실행기로 읽는다. 장면 경계, 고정 절차 애니메이션의 이벤트, 사용자 확인 cue는 결정적으로 매핑한다. AI 클립의 특정 동작은 설계상 후보 구간을 좁힌 뒤 제한된 프레임 샘플링·움직임 분석으로 이벤트 후보를 찾고, 후보 주변 프레임을 재확인한다. 프레임 차이는 움직임 후보이며 충돌·접촉 또는 개그의 의미를 단독으로 입증하지 않는다. 원본 PTS를 trim·속도·전환을 반영한 출력 프레임 및 48 kHz 샘플 좌표로 변환한다. 예상 삽입 시점은 검색 범위의 힌트로만 쓴다.
- AI Studio는 확인한 앵커만 동기화 큐로 확정한다. 판정 근거가 부족하거나 여러 후보가 경쟁하면 사용자에게 타임라인 프리뷰에서 선택·조정하게 한다. 중요도가 낮은 불확실 큐는 생략하거나 장면 단위 ambience/BGM/의도적 침묵으로 대체한다. 핵심 접촉·반전 큐의 동기화가 품질 조건이면 검수를 보류한다. 원본 클립 해시 또는 편집 타임라인 변경 시 관련 큐를 다시 검증한다. 확정된 음원 ID·해시, 위치·gain·fade, 앵커 근거·확인 방식·선택/생략 사유를 render plan과 manifest에 기록한다.

### 로컬 음원과 믹싱

- 기존 카탈로그와 권리 표현을 확장해 BGM·Foley·SFX·ambience를 등록한다. 음원별 고유 ID, 종류, 분위기·검색 태그, 실측 길이, SHA-256, 출처 URL, 저작자·표시 필수 여부와 문구, 라이선스/증빙, 상업 사용·편집·게시 가능 여부, 확인자·날짜를 보존한다. 라이선스가 불명확하거나 표시 조건을 충족할 수 없는 음원은 후보에서 제외한다. 사용 이력은 채널별 기존 요청·run의 확정 manifest에서 음원 ID/해시로 추적하며 별도 중복 카탈로그나 이력 시스템을 만들지 않는다. 크레딧 표시는 이후 게시 메타데이터로 인계한다.
- 보호된 FFmpeg graph를 확장해 narration/BGM/Foley/SFX/ambience별 gain과 이펙트 우선순위를 적용한다. 음성 중심 BGM ducking, Fade In/Out, 음악 간 겹침 구간이 충분한 경우 Crossfade, 의도적 BGM 컷과 무음을 지원한다. 코미디·전환 효과는 장면과 내레이션 큐에 맞추며 여러 효과가 겹치면 낮은 우선순위를 감쇠 또는 생략한다. 클립 원본 오디오를 기본 제외하고 앱이 믹싱한 오디오만 최종 MP4에 매핑한다. 프리믹스 headroom을 확보하고 최종 2-pass loudnorm 및 디코딩 AAC로 -18~−14 LUFS, true peak ≤ −1 dBTP를 검사한다. 내레이션 명료도·개그 타이밍은 자동 음량 검사에 더해 사람이 청취한다.
- 음악 수·전환 수·효과 수와 레이어 수, 이미지·동영상 비율은 콘텐츠에 따라 결정한다. 입력 길이·동시 처리량은 기존 보호 실행·scratch 자원 한도 내에서 검사한다. 상업 사용 가능한 로컬 라이브러리를 우선하며 매편 유료 음악 생성은 하지 않는다. 설계와 실제 렌더 승인은 3,000원 범위 및 자산 권리 계약에 따른다. 지식코리야는 상황 개그와 타이밍 연출만 참고하고 캐릭터·음원·대본·콘텐츠 복제를 금지한다.
- 구현 이후 기존 통합 인수에서 권리 불명·해시 불일치 차단, drop/fallback 이유, trim/PTS 재매핑, 허용 오디오만 매핑, ducking·fade·crossfade·중첩·피크, 의도적 침묵과 낭독 가독성, 중요 큐 사용자 확인을 검증한다. 별도 유료 스파이크를 추가하지 않는다.
