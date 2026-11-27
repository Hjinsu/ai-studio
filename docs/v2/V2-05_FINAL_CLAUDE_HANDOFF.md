# V2-05 구현 작업 분할 및 Claude 인수인계

**PROPOSED · 구현 착수 승인 대기** · [최종 설계](V2-05_FINAL_DESIGN.md) · [공통 계약 계획](V2-05_FINAL_COMMON_PLAN.md)

| 작업 | 담당 및 파일 소유권 | 선행/병렬 범위 | 인수 조건 |
|---|---|---|---|
| V2-05-COMMON | Codex. production/contract, execution, cost, 공통 media storage/API, migration, OpenAPI 및 생성 타입, 공통 기록 | 승인된 설계 기준에서 `v2/05-common` 제안. 제품 착수 기준 SHA를 공유 | 버전·SELECT·Worker·비용·복구·자산 계약 통과 |
| V2-05-ADAPTER | Claude. 승인된 미디어 공급자 어댑터 패키지와 모의 HTTP 테스트 | 공통 SHA 이후 `v2/05-product` 계열 별도 worktree. 승인된 mock 포트로 UI·렌더와 병렬 검증 가능 | Gemini TTS/FLUX/Wan 스키마·사용량·가격 및 실패·불명 처리 |
| V2-05-PRODUCT | Claude. Worker 제품 흐름, production/media/product, 프론트엔드 페이지·공통 UI 소비 코드, 제품 테스트 | 공통 타입 동결 후 Worker·렌더·UI를 서로 겹치지 않는 경로에서 분리 가능. 공통 DB/API 변경은 Codex에 요청 | 사용자 실행 파일 왕복, 믹스·동작 큐·권리·자막 OFF, 실제 보호 렌더 및 UI 인수 |
| V2-05-INTEGRATION / REVIEW | Codex. 별도 통합 worktree의 충돌 조정·검증 보고서. 독립 리뷰는 구현/통합과 분리된 Codex 세션 | 공통·제품 HEAD 수용 후. 동일 작업 트리 동시 수정 금지 | 최신 HEAD CI, 스코프별 회귀, 독립 리뷰, 사용자 확인 및 병합 승인 |

### Claude 구현 순서

1. 승인된 공통 SHA·포트를 확인하고 mock으로 사용자 실행 파일 내보내기/가져오기, 검수 결과와 라이브러리/오디오 큐 UI를 연결한다. SELECT 우회와 stale 결과를 차단한다.
2. 공식 API 및 가격·사용 조건을 활성화 직전에 확인한다. Gemini TTS의 스파이크 관측은 `v1beta/interactions`, 단일 Puck, part별 style, `store: false`, WAV 24 kHz mono다. 적용 SKU의 입력·출력 최대액과 응답 완전성이 입증돼야 한다. fal price의 단위(MP/초)·통화·단가 변경은 발송 차단, 동일 job 조회는 허용. 추가 공급자 및 T2V는 활성화하지 않는다.
3. 실측 낭독으로 장면을 배치하고 이미지·영상·절차적 연출을 조합한다. 오디오 큐는 실제 클립 시간으로 매핑하고 불확실한 핵심 큐를 프리뷰에서 조정할 수 있게 한다. 권리 확인된 로컬 음원을 선택하고 최종 MP4에는 앱 믹스만 매핑한다. IFNOVA 자체 ASS/SRT·번인 OFF 경로를 확인한다.
4. 사용자 인수는 40~60초, 세로 CFR30, 실제 피사체 이동·절차 애니메이션, 근거 있는 과학 코미디, 한국어 낭독·음원 균형·효과 타이밍을 확인한다. manifest에 소스·권리·편집 큐·비용·해시·QC 근거를 보존한다.

### 검증 및 사용자 인수

- **공통/통합** · 구·신규 파이프라인 회귀, 중복 파일 및 경합, stale owner/epoch, 미발송 예약·저장 이후 QC 장애, job ID 유실, UNKNOWN과 단일 정산. Windows 보호 실행기 사용, Linux 미검증 환경은 기존 fail-closed 유지. DB 검증은 격리 MariaDB에서 수행한다.
- **제품** · TTS incomplete와 길이 이탈의 미디어 발송 차단, 동적 예산 대체/보류, 혼합 렌더와 실제 동작 cue, 권리 미확인·해시 훼손 거부, narration ducking·fade/crossfade·중첩·무음, 원본 오디오 제외. 디코딩 AAC 기준 −18~−14 LUFS 및 ≤−1 dBTP, 첫 3초·가독성·청취 품질은 사용자 확인. 라이트·다크·모바일, 오류·권한·세션 만료, 인증된 Range/다운로드, 자막 OFF와 이전 프로필 동작을 검증한다.
- **유료 인수** · 실제 청구 대조 및 최대 청구 확인 후 별도 호출 승인. 모든 시도 포함 ≤3,000원, 불명 비용 0, 사용자 품질 PASS와 승인된 revision/MP4 해시. 제품·DB·유료 운영 및 게시 각각의 승인 경계를 유지한다. 추가 기술 스파이크는 배정하지 않는다.
