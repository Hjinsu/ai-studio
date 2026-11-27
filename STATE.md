# 작업 상태
기준선: v0.1 설계 패키지 + ADR-006~066 승인(2026-11-25). 실제 계정 및 API 미연동.
현재 상태: T02 DONE (2026-11-25 최종 승인). T03-A6 최종 명세 승인(ChatGPT APPROVE). FD-01~16 유지, T03-B만 FV-01~06을 조건으로 착수 승인. [T02 통합 보고서](docs/reports/T02_REPORT.md), [최종 독립 검토](docs/reviews/T02_FINAL_PATCH_REVIEW.md).
| ID | 상태 | 작업 |
|---|---|---|
| T01 | DONE (2026-11-25 승인, 커밋 55e33ec, CI run 45440907314) | 프로젝트 bootstrap, 버전 고정, DB/Flyway, CI, health check |
| T02 | DONE (2026-11-25 최종 승인, 제품 `55ea852`, [PR #1](https://github.com/Hjinsu/ai-studio/pull/1), 사용자 검증 14/14·최종 재확인 6/6) | 인증, 채널, 설정, 비용 원장 |
| T03 | B DONE (PR #3의 최종 HEAD 전체 CI 성공 및 t03/integration 병합 시 효력; 병합 전 IMPLEMENTED), C~F BLOCKED | 콘텐츠 revision, DB 큐, lease, 재시도 |
| T04 | TODO | fake provider, 조사/대본/스토리보드 |
| T05 | TODO | 이미지/TTS/자막/FFmpeg 로컬 렌더링 |
| T06 | TODO | 검수/승인/관리자 UI |
| T07 | TODO | 실제 provider 소액 검증 (허가 필요) |
| T08 | TODO | OAuth/private upload/예약 (허가 필요) |
| T09 | TODO | 성과 분석, 단일 채널 실험 |
| T10 | TODO | 트렌드 및 멀티채널 확장 |
## 인수인계 기록
작업 ID / 상태 / 변경 파일 / 테스트 명령 및 실제 결과 / 미해결 문제 / 다음 작업 / 필요 환경변수. 커밋 해시는 생성된 경우만 기록.

### V2-05-DESIGN — 상세 설계 (2026-11-27 KST, PROPOSED)

- 담당 Codex. 브랜치 `v2/05-design`, 설계 착수 기준 `9ca44233b402c6a26e5708c4182453b97d3f2045`. `origin/v2/integration` `e51e64971a9e16251b38f86e23f7c778e7eb11eb`과 V2-04 DONE, 최종 B01·B02 수정의 조상 포함 관계를 확인했다.
- 변경: [상세 계획](docs/v2/V2-05_IMPLEMENTATION_PLAN.md), [저장소 대조](docs/v2/V2-05_REPOSITORY_DESIGN.md), [공급자·스파이크](docs/v2/V2-05_PROVIDER_SPIKE.md), IFNOVA 요구사항·기존 공급자 조사·v2 색인, TASKS·DECISIONS·STATE. 문서 범위.
- 검증: Git 원격 SHA·조상 확인, staged diff check 및 Gitleaks. 제품 테스트 미실행, Migration 생성·적용 없음. 실제 청구·공급자 장애 복구·Linux 제품 경로는 미검증이다.
- 다음: 사용자 설계 승인과 구현 배정 후 공통 브랜치에서 모의 공급자와 격리 MariaDB 검증을 착수한다. 실제 호출은 공급자·SKU·권리·최대 지출을 별도 승인받은 스파이크로 한정한다.

### V2-04-BLOCKING-FIXES — 독립 리뷰 B01·B02 수정 (2026-11-27 KST, DONE)

- Claude Code(Codex 사용량 제한으로 위임). 기준 `v2/04-integration` `b9669aae508c71fec7fabeefaab7d1adade09fe0`, 브랜치 `v2/04-blocking-fixes`. 상세·재현 명령: [수정 보고서](docs/reports/V2-04_BLOCKING_FIXES_REPORT.md).
- B01: 예약+바인딩 커밋 후 발송 허가 전 중단 시 RECOVERY_REQUIRED 고착. `EffectStages`가 ACTIVE 바인딩 item을 execute로 재제시해 같은 operation·효과 키로 기존 permit이 단일 발송/미발송 종료/불명 유지를 판단한다. `ProductionCostService.recover`는 발송 금지 모드 permit으로 취소·기간 만료를 미발송 영수증으로 닫는다. B02: QC 3차 결과 커밋 후 성공 커밋 전 중단 시 `INTERRUPTED` 실패. `StepPlanner`가 영속 APPLIED 결과가 있는 중단 로컬 step을 시도 증가 없이 Recover하고, 기존 `verifyQc` 해시 재검증 뒤 기존 성공 트랜잭션으로 확정한다.
- 테스트: 수정 전 기준 SHA에서 새 테스트 8건 실패 재현. 수정 후 `--rerun-tasks` 단위 총 331·통과 330·실패 0·SKIP 1(Linux 전용), 격리 MariaDB 11.8.2·FFmpeg 9.1 통합 총 277·통과 276·실패 0·SKIP 1(기존 liveEdge 게이트), bootJar 성공. diff --check·변경 파일 Gitleaks 통과.
- Migration·OpenAPI·공통 계약·scratch 보호 변경 없음. 사용자 DB·유료 호출·게시 없음. 미검증: OS 수준 JVM kill(영속 상태 기반 주입으로 대체, 보고서 §4), Linux 실행.
- 잔여 B01(Codex 재검증 UNRESOLVED): fixture 준비 전에 fixture 없는 recover(may-not-send)로 취소·만료 미발송 예약을 먼저 종료하도록 `EffectStages`만 수정. 수정 전 신규 14건 중 8건 실패, 수정 후 집중·관련 통합 총 82·통과 81·SKIP 1, 단위 총 331·통과 328·SKIP 3(환경·OS 게이트). 보고서 §9.
- Codex 최종 독립 검증 **APPROVE** (32bcff9f3995ed4c18b0145086400e95b71a548d). B01·B02 RESOLVED, 신규 BLOCKING 없음. 사용자가 검증 결과를 수용했고 v2/04-integration에 fast-forward로 통합했다.
- B01·B02 수정과 최종 독립 리뷰 APPROVE를 사용자 최종 인수에서 확인했다.

### V2-04-INTEGRATION — 제품·안전성 결합 (2026-11-27 KST, DONE)

- `v2/04-integration` / `../ai-studio-v2-04-integration`에 Safety `5c303a2aebe87222df6d2b5b8aad182cf70a0716`와 최신 Product `925d736225828c720f11bc95569f84a6a17165f3`를 결합했다. 병합 커밋 `c187b76e68e655a66278b6f2212cce928336c1c6`. Safety IMPLEMENTED, Product DONE.
- 충돌 해결, 제품 설정, 검증 절차, Windows Job 경계 테스트, V2-05·V2-06 요구사항과 전체 기술 결과를 [통합 보고서](docs/reports/V2-04_INTEGRATION_REPORT.md)에 기록했다. 기존 V13·OpenAPI·생성 타입은 공통 SHA와 일치하며 V13 적용은 격리 DB로 제한했다.
- 사용자 최종 인수 및 병합 승인에 따라 `fb0e004ba76becc41e78ca8aa6c34267b1046afa`를 `v2/integration`에 반영했다. 최종 독립 리뷰 APPROVE, B01·B02 RESOLVED, 신규 BLOCKING 없음. Linux의 실제 배포 제품 렌더와 B01·B02의 OS 수준 JVM 강제 종료는 미검증이다.

### V2-04-SAFETY — Scratch 예약 및 정리 결함 (2026-11-27 KST, DONE)

- 사용자 승인 범위는 `v2/04-safety`의 scratch 예약·정리 수명주기다. 기준 Safety `59f22c8ab026029fe2a6c2eb20e2666f6c0a51ec`, Common `d7bec5f9aadcc423d7b2a987ac31d810367b4604`, Product `1c526763d3b14b5f5721bb451e2bc09c6f669a2a`.
- `MediaProcessRunner`, `ProductionExecutor`, `MediaFlow`, `MediaAvailability`, `ProtectedMediaProcessRunner`, `MediaCleanup`에 단계 경계·활성 예약·검증된 scratch 정리를 연결했다. `PlanningExecutionIT`에 해시·삭제 실패·lease 보호·취소 시 증거 보존 회귀를 추가했다. 변경된 수명주기와 집중 검증은 [안전 보고서](docs/reports/V2-04_SAFETY_REPORT.md#scratch-예약-수명주기-수정-2026-11-27-kst)에 기록했다.
- `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 단위 298건, 격리 DB 통합 260건 통과. Windows Linux 전용 테스트와 기존 선택 Live Edge 테스트 각 1건 건너뜀. 16 GiB의 `S:/s/v204-verify`에서 제품 소스를 임시 조합한 최종 bootJar로 3개 채널의 영상 5편을 연속 제작했다. 요청 사이 수동 scratch 삭제 없이 5건 모두 11단계 첫 시도에 성공했다.
- Migration 변경 없음. V13은 격리 MariaDB 11.8.2에만 적용했다. 다음 작업은 원격 safety HEAD 기준의 별도 독립 리뷰와 사용자 승인 범위의 제품 결합 검증이다.

### V2-04-PRODUCT — 제품 기술 검증·사용자 인수 완료 (2026-11-27 KST, DONE)

- Claude Code, 공통 SHA `d7bec5f9aadcc423d7b2a987ac31d810367b4604`, 브랜치 `v2/04-product`. 상세·재현: [진행 보고 §0](docs/reports/V2-04_PRODUCT_REPORT.md). 사용자 지시로 이 브랜치 STATE.md에 직접 기록(통합 시 정리).
- 완료(체크포인트 `0920437`): `backend/.../production/media/product/`, 프런트 미디어 미리보기, fixture 생성기. 회사 PC 재개 세션에서 `frontend/e2e/live-media.mjs`(실제 앱 인수 스크립트, npm test 제외)를 추가했다. 제품 코드·공통 계약·migration·OpenAPI 변경 없음.
- 테스트(회사 PC, 격리 MariaDB 3307): `:backend:test` 324/324, `:backend:integrationTest` 259 통과·실패 0·건너뜀 1(기존 환경 게이트 liveEdge), `:backend:bootJar` PASS. frontend lint·typecheck·Vitest 102/102·build PASS. Gitleaks no leaks.
- **실제 앱 E2E PASS**: bootJar + 공통 보호 실행기(Windows Job Object, 16 GiB 전용 VHDX `S:\s`) + Vite + Edge. 로그인 → 발굴 → UI 소재 선택 → 11단계 각 1회 SUCCEEDED → QC 32/32 → 재생·seek 30 s(206)·장면 이미지/낭독·MP4/SRT 다운로드(목록 SHA 일치)·라이트/다크·모바일. MP4(Git 밖 `C:\Users\PC-94\Downloads\AI-Studio-V2-04-Acceptance\output-pc94-e2e\`): neutron-star `16f7d2da0713288b715b6964a0ed3c91c4ea0bd42121698dfbcaaf445590c4c6`, venus-day `fcf8d4d8d1092d12fd6193a65e2e70b9730fc80683cc6abf6a90805e8eb6cb33`.
- Scratch 누적 결함은 Safety 기준 SHA에서 해결했다. 제품 보고서 §0.5의 재현은 이력이다.
- 사용자가 실제 MP4 두 편의 낭독·자막·배경음악·효과음과 자산 권리 근거를 모두 PASS로 확인했다. 정지 이미지 중심 표현은 V2-04 범위로 수용했고, 실제 움직임과 콘텐츠 재미 개선은 V2-05로 이관했다. Product `925d736225828c720f11bc95569f84a6a17165f3`. [Claude 제품 보고서 §0.6](https://github.com/Hjinsu/ai-studio/blob/925d736225828c720f11bc95569f84a6a17165f3/docs/reports/V2-04_PRODUCT_REPORT.md#06-기술-qc와-사용자-확인-2026-11-27-kst)
- 후속(범위 밖): V2-05 IMAGE_TO_VIDEO·TEXT_TO_VIDEO 검토, V2-06 AI 콘텐츠 고지·실제 게시 정책 검증. 실제 게시·유료 API 호출 미승인.
- 통합 SHA의 CI와 최종 독립 리뷰를 통과했고 사용자 최종 병합 승인에 따라 종료했다.

### V2-04-COMMON — 공통 계약 구현 (2026-11-27 KST, DONE)

- 사용자 착수 승인 기준 `715893fa47a955be399492ddc362b169b528dae3`, 설계 승인 `18c9b9484d6bc6637252732adb0063e7808ca960`, Codex `v2/04-common` / `../ai-studio-v2-04-common`. 공통 검증 커밋은 `origin/v2/04-common`에서 확인한다.
- V13 및 격리 스키마 테스트, 캐릭터 없는 PROFILE snapshot, 미디어 port·저장·실행/정산·복구, Windows Job Object, HTTP Range 및 OpenAPI 생성 타입을 추가했다. 주요 경로는 `backend/src/main/java/com/aistudio/production/{api,contract,cost,execution,media,planning}`, 신규 migration 및 권한 템플릿, `docs/api/openapi.yaml`, `frontend/src/api/generated/schema.d.ts`, 대응 테스트다. 미디어 기능은 기본 비활성이다.
- `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 및 `AI_STUDIO_REVIEW_EDGE=true`: 단위 296건, 격리 MariaDB 11.8 통합 257건, 실패·건너뜀 0. V13 신규·기존 스키마, 운영 최소권한, 기본 11단계, 캐릭터 없는 옵트인, FAKE 파일 저장 실패 뒤 렌더 준비 불가 상태의 한 번 정산, HTTP 206/416/HEAD/401, 실제 Edge 회귀를 확인했다. Windows Job Object의 생성·출력, timeout 종료, JVM 강제 종료 후 자식 종료를 확인했다.
- 프런트 lint/typecheck/Vitest 97건/build, Edge 모의 E2E 14건, OpenAPI lint/생성 타입 일치 및 `git diff --cached --check`·Gitleaks 통과. OpenAPI의 기존 CSRF 4XX·미사용 PageMeta 경고 2건 유지. V13은 격리 테스트 DB에만 적용했다. 기존 Migration 변경, 사용자 DB 접속, 실제 유료 호출, 게시, 통합 병합 없음.
- 다음 worktree는 동일 공통 SHA의 Codex `v2/04-safety` / `../ai-studio-v2-04-safety`, Claude `v2/04-product` / `../ai-studio-v2-04-product`다. Codex는 Linux 보호 검증 및 안전 회귀를 이어가고, Claude는 [착수 지시서](docs/v2/V2-04_CLAUDE_HANDOFF.md)에 따라 실제 렌더·제품 QC·미리보기를 구현한다. Linux 미디어 실행은 안전 차단 상태다. 권리 확인된 실제 낭독·글꼴·FFmpeg로 서로 다른 두 편을 만드는 제품 인수와 독립 리뷰는 후속이다.
- 최신 제품 결정에 따라 고정 캐릭터 모델링·복수 화자·캐릭터 레퍼런스 생성·일관성 검수·Blender는 [V2-05 BACKLOG](docs/v2/V2-05_IFNOVA_CONTENT.md)에 기록했다.

### V2-03 — 최종 승인 및 종료 (2026-11-27 KST, DONE)

- 사용자가 최종 집중 리뷰 APPROVE를 수용하고 [PR #6](https://github.com/Hjinsu/ai-studio/pull/6) 병합을 승인했다. 승인 SHA `42cbbcfd2762306e3cd6ec7dced8c6b864325cda`의 [CI](https://github.com/Hjinsu/ai-studio/actions/runs/45830743931) 7/7과 병합 가능 상태를 확인했다. Draft 해제 후 `v2/integration`에 `0aa11d0ea2eec7333cb901bc0ac140a8b414d82d`로 병합했고 Git 트리가 승인 SHA와 일치한다.
- 기존 집중 리뷰의 단위 30건과 승인 SHA의 격리 MariaDB 통합 255건(실제 API Edge 포함)을 근거로 종료했다. 이번 종료 단계에서는 리뷰·통합 테스트를 반복하지 않았다. Migration 파일은 승인 트리와 동일하고 추가 DB 적용은 없다.
- 비차단 후속: V2-05에서 실제 AI의 사실 주장·의미상 중복 검증. OpenAPI 문서 정비에서 CSRF GET의 4XX 응답 미정의와 미사용 `PageMeta` 경고를 검토한다.
- `origin/docs/v2-provider-discovery`의 문서 변경 `9c07d0e`를 별도 병합 커밋 `eb35c36236753abccef118230baa02b1c8438756`으로 반영했다. 공급자·소재 발굴 설계는 PROPOSED다. 다음 작업은 별도 승인 범위의 V2-04 설계·착수 확인이다.

### V2-03 — F05 화면비 수치 예외 제거 (2026-11-26 KST, HOLD)

- `v2/review-v2-03` 기준 `e0083695192c1a01253e1e1d138ba4ed0bc6864c`. `ScenesReview`, `PlanningExecutionService`, `FakePlanningProvider`, SCENES fixture, 관련 백엔드·프론트 테스트와 화면 레이블을 갱신했다. 설계 승인은 `DECISIONS.md`와 V2-03 구현 계획, 검증과 인수인계는 `TASKS.md`, `docs/NEXT_SESSION.md` 및 결합 보고서에 기록했다.
- 새 SCENES 입력만 버전을 올렸다. 구버전 영수증은 저장 증거로 재생하며, 미적용 상태에서 재검수할 때 대본 근거가 없는 수치는 비용을 정산한 뒤 차단한다.
- 단위 293건, 격리 MariaDB 11.8.2 통합 255건(실제 API Edge 포함), bootJar, 프론트 lint·typecheck·build, Vitest 97건, Mock Edge 14건, OpenAPI 검사 및 생성 타입 일치, Gitleaks, diff 검사 통과. 두 소재별 전체 11단계 완료, 버전 1 영수증 두 경로 및 F01~F04·F06을 검증했다. V12는 격리 DB에만 적용했고 기존 Migration 파일은 기준과 동일하다.
- 다음 작업은 [Draft PR #6 최신 HEAD의 CI](https://github.com/Hjinsu/ai-studio/pull/6/checks)와 SHA를 확인한 별도 읽기 전용 독립 리뷰다. 사용자 승인까지 HOLD를 유지한다. [결합 검증](docs/reports/V2-03_INTEGRATION_REPORT.md#f05-화면비-수치-예외-제거-2026-11-26-kst)

### V2-03 — F05 최종 수정 검증 (2026-11-26 KST, HOLD)

- 기준 `eabf980780d06474767f6195895c3b0df84a926b`에 Claude `1d31f5046bce52cc70a6fb4236b7b05abc079b5c`를 별도 `ai-studio-v2-review-03` worktree의 `v2/review-v2-03`에서 fast-forward했다. 변경 제품 파일은 `ScenesReview.java`, `ScenesReviewTest.java`다. 인수 문서는 `STATE.md`, `TASKS.md`, `docs/NEXT_SESSION.md`, `docs/reports/V2-03_INTEGRATION_REPORT.md`다.
- 이전 HOLD 사례 여섯 개는 수치 검수에서 차단됐다. 콜론·괄호·긴 대시·쉼표가 사실 비율을 화면비 지시처럼 분리하는 신규 반례 네 개가 재현됐다. 다음 작업은 Claude 담당 F05 경계 보완과 회귀 추가, 결합 및 최신 HEAD의 CI 확인, 별도 독립 재검토다.
- 백엔드 단위 293건(ScenesReviewTest 22건), 격리 MariaDB 11.8.2 통합 253건(실제 API Edge 1건 포함)과 bootJar 통과. V12는 격리 DB에만 적용했고 임시 서버의 정상 종료를 확인했다. Vitest 97건, Mock Edge 14건, 프론트 lint·typecheck·build, OpenAPI lint(기존 경고 2건)와 생성 타입 검사, 게시 이력 Gitleaks, diff 검사 통과. Migration 파일은 기준과 동일하다. [결합 보고서](docs/reports/V2-03_INTEGRATION_REPORT.md#f05-최종-수정-결합-및-검증-2026-11-26-kst) · [Draft PR #6 검사](https://github.com/Hjinsu/ai-studio/pull/6/checks)

### V2-03 — 독립 리뷰 HOLD 수정 (2026-11-26 KST)

- 독립 리뷰 기준 `54ffea3c09246b61a057c427e6210591af20cccb`, 수정 브랜치 `v2/review-v2-03`, [Draft PR #6](https://github.com/Hjinsu/ai-studio/pull/6). 사용자가 HOLD를 수용했다. 별도 독립 리뷰 APPROVE와 사용자 승인 전까지 병합·DONE을 보류한다.
- F01은 UTF-8 저장 크기·지적 수·필드 길이를 제한하고 차단 지적을 우선 보존한다. F02는 공급자 응답 반환 후 형식 검사를 결과 불명과 분리한다. F03은 SCRIPT 비교 목록을 한 번 읽어 예약과 재생에 사용한다. F04는 요청 생성과 claim에 같은 공급자 준비 조건을 적용한다.
- 변경 경로는 planning 서비스·검수 기록·준비 상태, 비용 서비스, 요청 API·실행기 설정, 격리 DB와 실행기 테스트, 실제 API Edge 스크립트다. Codex 커밋은 `ec10c07`이다.
- Claude F05 `5f8dfe3`와 F06 `a6fa744`를 `e139cef`에서 결합했다. F05 후속 `8fd969a`를 `9b952e6`에서 인수했고, `782b6d5`에서 `vertical 9:16 ratio of the tower` 등 사실 비율과 프레이밍 표현이 붙은 경우도 검수 대상으로 유지했다.
- `e139cef`에서 `AI_STUDIO_REVIEW_EDGE=true`와 `gradlew.bat --no-build-cache :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks`로 단위 287건, 격리 MariaDB 11.8.2 통합 253건(실제 API Edge 1건 포함), 실패·오류·건너뜀 0건, bootJar 성공. 정산 영수증 새 lease 적용, SCENES 형식 오류·로컬 소켓 타임아웃 분리, 대본 21개 이상에서 최초 20개 고정·재생, V11 기존 데이터 보존, 제한 운영 계정 11단계, 소유자 200·다른 인증 사용자 404·미인증 401, LONGFORM 및 길이 범위를 변경한 SHORTS를 확인했다.
- 최종 코드 `782b6d5` 재실행에서 단위 292건, 격리 통합 253건(실제 API Edge 포함), Vitest 97건, Mock Edge 14건이 통과했다. 다음 작업은 [Draft PR #6 최신 HEAD의 CI](https://github.com/Hjinsu/ai-studio/pull/6/checks)를 확인한 별도 독립 리뷰다. F05의 괄호 화면비 표현은 검수 대상이 될 수 있고 비영어 화면비 표현은 지원 범위 밖이다. 일반 자연어 의미 검증은 V2-05에서 다룬다. [최종 결합 검증](docs/reports/V2-03_INTEGRATION_REPORT.md#hold-수정-결합)
- V12 적용은 격리 테스트 DB로 제한했다. 기존 migration, OpenAPI 및 생성 타입에는 변경이 없다.

### V2-03 — 제품·안전성 결합 검증 후보 (2026-11-26 KST)

- `v2/review-v2-03`, 통합 기준 `c4832b7`, 공통 `610b9f8`, 제품 `5694941`, 안전성 `4299c69`. 두 브랜치의 이력을 병합 `c1fdff1`로 보존했다. [변경 파일과 검증 결과](docs/reports/V2-03_INTEGRATION_REPORT.md)
- 실제 저장·비용 포트로 AUTO/MANUAL 11단계, 장애·비용·품질 차단을 확인했다. 백엔드 단위 279건, 격리 통합 241건, 선택 실행 실제 API Edge 1건, Vitest 97건, Mock API Edge 14건 통과. V12는 격리 DB에만 적용했다. V1~V12 파일·OpenAPI·생성 타입은 공통 기준과 동일하다.
- 다음 작업은 최신 Draft PR HEAD와 CI를 확인한 뒤 별도 세션 독립 리뷰다. 사용자 최종 승인 후 종료와 병합을 검토한다.

### V2-02 — 최종 승인 및 종료 (2026-11-26 KST, DONE)

- ChatGPT 설계·구현 검토와 별도 Codex 독립 재검토 APPROVE, P0/P1 없음, 사용자 최종 종료·병합 승인. 검증 SHA `9acab98483539402ee61e9695a3d3ccf10b66f00`의 [CI 45758360558](https://github.com/Hjinsu/ai-studio/actions/runs/45758360558) 7/7 통과.
- [PR #5](https://github.com/Hjinsu/ai-studio/pull/5)를 Ready로 전환하고 `v2/integration`에 merge commit `8ca054dc489d55e763a0f91790366df282550b27`으로 병합했다. 병합 Git 트리 전체가 승인 SHA와 일치한다.
- HOLD 해소: 최초 수동 확인 뒤 별도 트랜잭션에서 추가된 검수 근거로 선택을 무효화했다. 링크 없이 SELECT 재대기 및 lease 반납, 최초·최신 평가 보존, 오래된 토큰 거부, 명시적 재확인 후 단일 링크, 발굴 재호출·추가 비용 없음이 검증됐다. 기존 동시 SELECT 락 경합 회귀도 통과했다.
- 별도 독립 실행 `gradlew.bat :backend:integrationTest --tests '*DiscoveryExecutionIT' --rerun-tasks`는 격리 MariaDB 11.8.2에서 17건 통과, 실패·오류·건너뜀 0건. 구현 세션의 전체 테스트 결과와 격리 DB migration 이력은 아래 기록을 참조한다. 종료 작업은 문서 변경으로 제품 테스트를 재실행하지 않았다.
- P2 규칙 기반 사실 주장 탐지·의미상 중복 판정 한계, P3 FAKE 완료 문구는 [로드맵](docs/v2/03_ROADMAP.md)의 비차단 후속 항목이다. 다음 작업은 별도 사용자 착수 승인 후 V2-03 설계를 최신 `origin/v2/integration`과 대조하는 것이다.

### V2-02-HOLD-FIX — 수동 검수 재확인 경합 검증 (2026-11-26, 구현 인수인계)

- 기준 `735fb60576fc531e4b4f77fbf210d56abe9e9088`, 대상 Draft PR #5의 `v2/review-v2-02`. 별도 detached worktree에서 회귀 테스트와 로드맵을 변경했으며 제품 코드·API·기존 migration은 변경하지 않았다.
- `DiscoveryExecutionIT.manualReviewMustBeConfirmedAgainWhenAnotherContentAddsANewUncertainComparison`: 실제 평가기·저장·비용·선정 포트와 격리 MariaDB, 스크립트 FAKE 공급자를 사용한다. 최초 검수 근거 확인 후 다른 콘텐츠와 revision을 별도 서비스 트랜잭션으로 커밋하고 SELECT를 실행하여 새 검수 사유·선택 무효화·재대기·재확인·최종 연결 및 발굴 비용 보존을 확인한다. 실행 순서를 고정한 트랜잭션 경계 검증이며 두 트랜잭션이 동시에 잠금을 경합하는 테스트는 아니다.
- 신규 테스트는 기존 제품 코드에서 통과했다. `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar --rerun-tasks`는 단위 210건·통합 223건(실패·건너뜀 0) 통과했다. 격리 MariaDB 11.8.2, 127.0.0.1:3397, binlog ON을 사용했으며 기존 수동 선정·동시성 및 V2-01 비용 회귀가 포함된다. 프론트 lint·typecheck·build 및 Vitest 89건 통과. 최신 PR HEAD CI는 푸시 후 확인한다. 기존 FAKE UI 문구 및 사실 주장·의미 중복의 규칙 기반 판단 한계는 `docs/v2/03_ROADMAP.md`에 후속 항목으로 기록했다.
- DB: 사용자 DB 미접속. 격리 MariaDB에 V1~V11 적용, 기존 migration 수정 없음. 실제 공급자·게시 미사용. 다음 작업은 새 PR HEAD의 CI 확인과 별도 독립 리뷰 세션의 재검토다. 최종 종료 및 병합은 별도 승인 대상이다.

### T03-B-CLOSE — 종료 및 통합 승인

- ChatGPT 최종 APPROVE 및 사용자 종료·병합 승인. 제품 수정 `b5ffc73`의 RV-B-01~06과 계약 통합 `8cfe8a0`의 OpenAPI A1~A8을 수용했다. 로컬 단위 151건, 격리 MariaDB 통합 153건, 프론트엔드 73건 및 계약 통합 CI 7/7 성공을 확인했다. [검증 CI 45637703367](https://github.com/Hjinsu/ai-studio/actions/runs/45637703367)
- 종료 문서 HEAD는 [Draft PR #3 검사](https://github.com/Hjinsu/ai-studio/pull/3/checks)에서 독립 검증한다. 해당 HEAD의 CI 전체 성공과 병합이 모두 성립하면 통합 브랜치의 T03-B 상태는 DONE이다. 검증 또는 병합 실패 시 IMPLEMENTED가 유효하다. [PR #3 병합 및 SHA 기록](https://github.com/Hjinsu/ai-studio/pull/3)
- V6 일회성 예외와 LF SHA-256, 운영 binlog 트리거 배포 게이트, D 완료 후 budgetSummary required 전환 및 C/D 장애·비용 복구 검증을 종료 후에도 추적한다. [통합 보고서](docs/reports/T03_B_INTEGRATION_REPORT.md)
- C~F는 BLOCKED. T03-C의 착수 기준은 PR #3 병합으로 생성된 t03/integration SHA이며, 별도 사용자의 착수 승인 후 배정한다. [작업 범위 및 C-G01~10](docs/plans/t03-final/T03_IMPLEMENTATION_HANDOFF.md#t03-c-착수-배정-준비)

### T03-B-INT — 제품 계약 통합

- 독립 리뷰와 표적 재리뷰를 보존했다. 제품 기준 `b5ffc73`에서 RV-B-01~06 수정 검증을 완료했다(CONDITIONAL_APPROVE).
- Topic·Content·Revision 제품 OpenAPI 10개 작업 및 타입 생성, 제품 계약 검증과 C-G01~10 인수 조건은 [통합 보고서](docs/reports/T03_B_INTEGRATION_REPORT.md)에 기록한다.
- B는 IMPLEMENTED 상태로 사용자 종료·병합 승인을 기다린다. C~F는 별도 착수 승인 전 BLOCKED다.

### T03-A6 — 최종 구현 명세 작성 (2026-11-25)

- 기준 main 47ef697. FD-01~16 승인 사항을 ADR-067~076과 [최종 명세](docs/plans/t03-final/README.md)에 반영했다. SQL·OpenAPI는 검토 초안이다.
- 변경 범위는 최종 패키지, DECISIONS.md, TASKS.md, STATE.md 및 문서 인덱스다. 실제 migration과 OpenAPI 계약, 사용자 DB 적용은 수행하지 않았다.
- Git 상태·diff, 문서·JSON 구문/참조 정적 확인. 구현·DDL 적용·통합/성능 테스트·CI는 미실행.
- 최종 승인 기록(2026-11-25). ChatGPT APPROVE 및 사용자 명세 승인. T03-B만 조건부 착수 가능, C~F는 BLOCKED. Claude Code의 다음 작업은 별도 worktree의 t03/b-domain에서 V6과 도메인 계층을 구현·검증하는 것이다. [배정 및 FV-01~06](docs/plans/t03-final/T03_IMPLEMENTATION_HANDOFF.md).

### T02-FINAL-CLOSE — 종료 승인 및 main 반영 (2026-11-25, DONE)
- RVX-02-CB 독립 검토 PASS, 종료 권고 APPROVE, 새 종료 차단 결함 없음. 최종 검토 원문과 사용자 검증 14/14·최종 재확인 6/6을 보존했다.
- 제품 `55ea852`, 종료 전 기준 HEAD `49334bc`의 CI [45578405327](https://github.com/Hjinsu/ai-studio/actions/runs/45578405327) 7/7. [PR 병합 및 최신 검사 기록](https://github.com/Hjinsu/ai-studio/pull/1/checks).
- 백엔드 단위 133건·격리 MariaDB 통합 97건은 기존 T02 기록, 프론트엔드 Vitest 73건·Edge Mock API E2E 10건은 최종 보강 기록을 근거로 한다. 실제 백엔드 연결 브라우저 E2E 미실행. 종료 작업에서 제품·API·DB·migration 변경 없음.
- CR-01·CR-02, P-T02-03·P-T02-05, UI-F06·F08·F09·F10 보류 및 T03 선행 제약 유지. 지출 차단을 유지하며 실제 비용 발생과 유료 API 활성화 전에 영속 정산 멱등성과 결과 복구가 필요하다. T03은 별도 설계 승인 전 미착수.

### T02-FINAL-PATCH — RVX-02-CB 최종 보강 (2026-11-25, IMPLEMENTED)
- Draft PR #1 제품 커밋 `55ea852`. 보호 mutation의 캐시 조회와 토스트를 요청 세대에 결합하고 후속 UI 처리 전에 세대를 재확인했다. [최종 리뷰 원문](docs/reviews/T02_FINAL_REVIEW.md), [보강 및 검증 기록](docs/reports/T02_FINAL_PATCH_REPORT.md).
- 기존 Vitest 70건에 표적 회귀 3건을 추가했다. 기존 Edge Mock API E2E 10건을 유지했다. API·백엔드·DB·migration 변경 없음. 개발 DB 미사용.
- 사용자 보고 화면 검증 14/14 및 최종 변경 화면 재확인 6/6을 유지한다. 다음 단계는 변경된 콜백 경계의 별도 세션 확인 및 최종 종료 승인이다.

### T02-UI-FIX-EXT — 세션 경계 및 정산 안전성 — Codex (2026-11-25, IMPLEMENTED)
- Draft PR #1의 통합 브랜치에서 `7256fe1`을 기준으로 제품 커밋 `53b305b`에 RVX-01~03을 반영했다. [표적 교차 리뷰](docs/reviews/T02_CODEX_UI_FIX_REVIEW.md)와 기존 검토 참조를 보존했다.
- 프론트엔드 인증 세대·정산 확인 흐름 및 해당 회귀 테스트를 변경했다. Vitest 70건과 Edge Mock API E2E 10건, lint·typecheck·build, OpenAPI lint 및 생성 타입 비교, gitleaks 결과는 [후속 수정 보고서](docs/reports/T02_UI_EXT_FIX_REPORT.md)에 기록했다.
- DB 및 마이그레이션 적용 상태 변경 없음. 개발 DB 3306, 실제 백엔드 브라우저 E2E, 유료 API 미사용.
- 다음 단계는 최신 Draft PR HEAD 기준 독립 교차 검토와 사용자 재확인·종료 승인이다. P-T02-05의 근본 해결은 실제 비용 발생 및 유료 API 활성화의 선행 조건이다. main 병합·DONE 전환·T03 착수는 별도 승인 대상이다.

### T02-UI-FIX — Codex UI 리뷰 결함·사용자 확인 항목 수정 — Claude Code (2026-11-25, IMPLEMENTED, DONE 아님)
요구사항: 사용자 지시(UI-RV-01~06 전체 수정, UI-F01·F02·F03·F04·F05·F07·F11·F12·F13, 안전 검토 A01·A02·A03). 리뷰 [T02_CODEX_FRONTEND_REVIEW](docs/reviews/T02_CODEX_FRONTEND_REVIEW.md), 결과 [T02_UI_FIX_REPORT](docs/reports/T02_UI_FIX_REPORT.md), ADR-066, 제안 P-T02-05.
- 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(Draft PR #1). 수정 전 HEAD `6f4589f`. 커밋 `646179a` "fix(T02-UI): resolve Codex UI review UI-RV-01..06 and approved UI-F items" → PR #1 푸시. GitHub Actions run [45567226915](https://github.com/Hjinsu/ai-studio/actions/runs/45567226915) **7/7 job success**(Backend Linux unit+integration, Backend Windows, Frontend ubuntu·windows, Frontend E2E Edge, gitleaks, OpenAPI·생성 타입). 이후 커밋은 기록용 문서만.
- 변경: `frontend/` 화면·공통 컴포넌트·테스트, 문서. **API·OpenAPI·생성 타입·백엔드·DB·Flyway 변경 없음**, 새 의존성 없음.
- 실제 결과: frontend lint·typecheck·build 통과, vitest **60/60**(기존 39 유지), Playwright E2E **10/10**(Edge, **Mock API** — 실제 백엔드 연동 아님, 3회 연속 통과), OpenAPI lint 유효(기존 경고 2)·생성 타입 일치, 백엔드 단위 **133/133**·통합 **97/97**(격리 임시 MariaDB 11.8.2 `127.0.0.1:3307`, 종료함)·bootJar 성공. 수정 전 코드(`6f4589f`)에 새 테스트를 적용하면 vitest 16건·E2E 3건 실패(재현 확인, 임시 worktree는 제거).
- 이전 CI 재실행: `6f4589f` run 45558365457 — 1차 Maven Central HTTP 429로 Backend Linux 실패, 2차 시도 7/7 성공.
- 사용자 검증(사용자 보고): 통합 UI 주요 화면 확인, 관리자·IFNOVA·예산 정책 6개·테스트 환율·지출 차단 보존, 정합성 검사 정상, 예약·원장 비어 있음 정상, 서버 상태 정상, 실제 유료 예약·정산 미실행.
- 미해결: A01 새로고침 후 수동 재입력 중복 가능성(P-T02-05, 실제 비용 발생 전 해결 또는 명시적 수용), UI-F06·F08·F09·F10 보류, 실제 백엔드 대상 브라우저 E2E 미실행, CR-01·CR-02(ADR-065).
- DB: 사용자 개발 DB(3306) 미사용·미변경, 새 migration 없음.
- 다음 작업: Codex 표적 교차 리뷰(TASKS `T02-UI-FIX-RV`) → 사용자 재확인 → 최종 종료 승인. main 병합·T02 DONE·T03 착수 금지(별도 승인).

### T02-INT — PR #1에 PR #2 통합 및 최종 검증 — Claude Code (2026-11-25, IMPLEMENTED, DONE 아님)
요구사항: 사용자 T02 조건부 통합 승인(ADR-063·064·065, P-T02-02 승인, P-T02-03 보류, P-T02-04 조건부 수용). 상세: [T02_REPORT §11](docs/reports/T02_REPORT.md).
- 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(Draft PR #1, base main). 통합 전 HEAD `862a300`, PR #2 승인 HEAD `6a32c64`(이후 새 커밋 없음 확인).
- 커밋: 협업 문서 `67d50b9` → 병합 `07f8963`(`--no-ff`, 충돌 없음) → UI 통합 보완 `8319672` → 문서(이 기록).
- 검증(통합 HEAD): 백엔드 단위 133/133, 통합 97/97(격리 MariaDB 3307), bootJar, 생성 타입 재생성 diff 없음·check 일치, 프론트 lint·typecheck·vitest 39/39·build, Playwright E2E 8/8(**Mock API**, 실제 백엔드 연동 아님).
- **CI**: 통합 HEAD `ab68286` → run **45557933535 전 7개 job 성공**(Backend Linux unit+Testcontainers integration+bootJar+image, Backend Windows, Frontend ubuntu/windows, Frontend E2E Edge, OpenAPI contract, gitleaks). 이후 커밋은 이 기록만 갱신하는 문서 커밋.
- PR #2: PR #1 브랜치를 대상으로 하므로 통합 푸시 후 GitHub가 **MERGED로 자동 표시**(2026-11-25T06:04:28Z). main 병합 아님, 브랜치·worktree 삭제 없음.
- 미완료: Codex UI 독립 리뷰(TASKS `T02-UI-RV`, 기준 `8319672`), 사용자 실제 화면 검증([T02_USER_VERIFICATION](docs/guides/T02_USER_VERIFICATION.md)), 최종 승인. T03 선행 제약은 [T03_PLAN](docs/plans/T03_PLAN.md)(착수 금지).
- DB: 사용자 개발 DB(3306) 미사용·미변경. 새 migration 없음(V1~V5 그대로). 임시 인스턴스만 사용 후 종료.
- 다음 작업: Codex UI 리뷰 결과 반영 여부 결정 → 사용자 실제 화면 검증 → 최종 승인 → (별도 지시) main 병합·PR #2·worktree 정리.

### T02-UI · T02 교차 리뷰 — Claude Code (2026-11-25, IMPLEMENTED, DONE 아님)
작업 ID: T02-UI(통합 UI 개선·디자인 시스템), T02-RV-X 교차 리뷰(Codex 백엔드 `fa32535..6a32c64`). 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(worktree `C:/Users/PC-94/Desktop/ai-studio`, Draft PR #1). Codex는 별도 worktree `../ai-studio-t02-fix`, 브랜치 `fix/t02-backend`(Draft PR #2, HEAD `6a32c64`) — 읽기 전용으로만 참조, 수정하지 않음.

**완료**
- UI: 커밋 `5d53065` — 디자인 토큰(라이트/다크), 공통 컴포넌트(`frontend/src/ui`, `frontend/src/layout`, `frontend/src/lib`), T02 전 화면 개편, 화면별 코드 분할, Playwright E2E(`frontend/e2e`, 설치된 Edge), CI job `frontend-e2e`. 문서 `docs/design/UI_DESIGN_SYSTEM.md`, ADR-063, API 보완 후보 P-T02-03. API 계약·생성 타입·백엔드 변경 없음.
- 교차 리뷰: `docs/reviews/T02_CLAUDE_BACKEND_REVIEW.md` — RV-01~09 해결, 신규 확인 결함 없음, 잠재 위험 CR-01·CR-02(P-T02-04), T03 제약, P-T02-02 범위 적절.

**실제 테스트 결과**
| 대상 | 결과 |
|---|---|
| UI(`5d53065`) lint·typecheck·vitest·build·E2E·생성 타입 check | 통과, vitest 36/36, E2E 8/8 |
| PR #1 CI run 45554314696 | 7/7 success(Backend Linux/Windows, Frontend ×2, Frontend E2E, OpenAPI, gitleaks) |
| 검토 worktree `../ai-studio-review-6a32c64`(detached `6a32c64`) + 임시 MariaDB 3307: `:backend:test :backend:integrationTest :backend:bootJar --no-build-cache --rerun-tasks` | 단위 133/133, 통합 97/97, bootJar 성공 |
| 같은 worktree에 UI(`5d53065` frontend) + Codex 생성 타입 결합 | lint·typecheck 통과, vitest 36/36, build, E2E 8/8, 생성 타입 일치 |
| `git merge-tree` UI ↔ `6a32c64` | 충돌 없음, 공통 수정 파일 0개 |

**미검증**: 개선된 UI의 사용자 실제 화면 재검증, 실제 백엔드 대상 브라우저 E2E, 통합 HEAD CI(통합 전), 로그인 부하·구버전 데이터 업그레이드 경합(CR-01·02).

**DB**: 3306 작업 DB 미사용·미변경. 임시 인스턴스(3307, scratch)만 사용 후 종료.

**공통 파일 주의**: 작업 트리의 `AGENTS.md`·`CLAUDE.md` 협업 규칙 수정, `TASKS.md`, `docs/guides/CODEX_COLLABORATION.md`, `docs/guides/LOCAL_RUN_GUIDE.md`, `docs/reviews/T02_CODEX_REVIEW.md`는 사용자/Codex가 추가한 미커밋 파일이다. Claude 커밋에 포함하지 않았다(AGENTS.md·CLAUDE.md에는 UI 디자인 시스템 규칙 줄만 별도 반영, TASKS.md는 작업 트리에서 갱신만). `T02_CODEX_REVIEW.md`는 `6a32c64`와 Codex worktree에 없다.

**다음 작업(사용자 승인 필요)**: (1) 통합 승인 → UI 브랜치에 `fix/t02-backend` 병합, `CHECK_CODE`에 `RESERVATION_BUCKET_LINK_MISMATCH`("예산 예약 연결 불일치") 추가, 통합 HEAD 전체 검증·CI (2) 사용자 UI 재검증 (3) P-T02-02·P-T02-04·ADR-063 결정 → 최종 승인 후 T02 DONE. T03 시작 금지.

### T02 — 관리자 CLI 결함 수정 (2026-11-25, IMPLEMENTED 유지, DONE 아님)
요구사항: 사용자 검증 결함(`java -jar ... admin create --login admin` → BeanCreationException), ADR-031. 상세: [T02_REPORT §8](docs/reports/T02_REPORT.md).
- **원인**: (1) `AdminCommandRunner` 생성자 2개에 `@Autowired` 없음 → Spring이 기본 생성자 탐색 실패. (2) Spring `ApplicationArguments`는 `--login admin`(공백)을 옵션 값으로 인식하지 않아 문서대로의 명령이 항상 "사용법" 거부.
- **변경 파일**: `backend/src/main/java/com/aistudio/security/AdminCommandRunner.java`(`@Autowired` 생성자 + `ObjectProvider<PasswordPrompt>`, 엄격 인자 해석 `parse`), `backend/src/main/java/com/aistudio/AiStudioApplication.java`(`adminCli()` 비웹 빌더 분리), `backend/build.gradle.kts`(integrationTest → bootJar 의존, jar 경로 전달), 테스트 `security/AdminCliIT.java`·`security/AdminCliJarIT.java`(신규), `AdminCommandRunnerTest.java`(+2).
- **실제 결과**: 단위 127/127, 통합 71/71(임시 MariaDB 3307). 실제 bootJar + local profile CLI 실행: create/reset-password/`--login=` → 컨텍스트 정상·웹 서버 없음·콘솔 없음 거부(종료 2), 인자 누락 → 사용법(종료 2). 변이 2종(`@Autowired` 제거, 이전 인자 해석) 모두 검출.
- **미검증**: 대화형 콘솔 숨김 입력 성공 경로의 실제 터미널 실행(이 환경에 TTY 없음) → 사용자 터미널에서 `java -jar backend/build/libs/backend-0.1.0-SNAPSHOT.jar admin create --login <id>` 재확인 필요. 3306 작업 DB 미사용·미변경.
- **CI**: 수정 커밋 `6d5cccd` → run 45530093712 전 job 통과(Backend Linux에서 bootJar → integrationTest 순서 확인, AdminCliJarIT 포함). Draft PR #1 유지, 병합 없음.
- **다음 작업**: 사용자 터미널 재확인 → 설계 담당 최종 검토 → T02 DONE 여부. T03 착수 금지.

### T02 — 2차 재개 세션: 설계 승인 반영·검증 완료 (2026-11-25, IMPLEMENTED, DONE 아님)
요구사항: P-T02-01 승인(→ADR-062), T02 미완료 항목(T02_PLAN §2 단계 7~10), 예산 필수 검증 14항목, ADR-035 관리자 UI. T03 미착수. 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(커밋·푸시 승인, main 병합 금지).

**커밋**: `77b4680`(OpenAPI 평탄화·계약 검증기 테스트·예산 IT), `b64fa3e`(예산 동시성·시간·롤백 IT, 운영 권한 IT), `6677f38`(관리자 UI·생성 타입 도구·CI job), `beb7e8f`(보고서·가이드), `e0ae07e`·`93bbde3`(gitleaks 오탐 예외), CI 결과 기록 커밋(`git log`로 확인).

**구현·변경**
- OpenAPI: allOf 10곳 평탄화(ADR-062, JSON 구조 불변). `unevaluatedProperties` 방식은 검증기의 중첩 스키마 미정의 필드 누락을 실측해 기각. 이전 테스트 헬퍼의 명세 변형(`strictAllOf`) 제거.
- 테스트(백엔드): `OpenApiContractTest`(단위 4), `budget/BudgetConcurrencyIT`(11), `budget/BudgetTimeIT`(5, MutableClock), `security/ProductionGrantsIT`(2), 이전 세션 추가분 `BudgetFailClosedIT`(1)·`BudgetLedgerIT`(6). Gradle 테스트 입력에 `docs/api/openapi.yaml`, `infra/mariadb/prod-app-grants.sql` 선언.
- 프론트: `frontend/tools/openapi-typescript`(격리 도구, 자체 lockfile), `src/api/generated/schema.d.ts`(생성물), `src/api/{http,admin}.ts`, `src/ui/*`, `src/pages/*`(T02 화면 전체), `routes.tsx`, `Layout.tsx`, `queryClient.ts`, 테스트 3파일. react-router 8.5.3, @testing-library/user-event 14.6.13 고정.
- CI: `openapi-contract` job(Redocly lint + 생성 타입 diff).
- 문서: DECISIONS(ADR-062, P-T02-01 승인), docs/reports/T02_REPORT.md, docs/guides/LOCAL_TEST_GUIDE.md(임시 DB 실행·종료 절차), docs/16_SOURCES.md, docs/00_INDEX.md, .gitignore(`backend/bin/`).

**실제 테스트 결과**
| 명령 | 결과 |
|---|---|
| `./gradlew.bat :backend:test :backend:integrationTest --rerun-tasks` (임시 MariaDB 11.8.2 :3307) | 단위 125/125, 통합 69/69, 실패·skip 0 |
| frontend `npm run lint`·`typecheck`·`test`·`build` | 성공, vitest 26/26 |
| tools/openapi-typescript `npm ci`·`lint:openapi`·`check` | Redocly 유효(경고 2, 의도), 생성 타입 일치 |
| 백엔드(임시 인스턴스의 `ai_studio`)+Vite 프록시 API 스모크 | 전 단계 기대대로(T02_REPORT §5) |
| 변이 검사 4종 | 모두 검출, 원복 확인 |
| 비밀정보 | gitleaks 로컬 미설치(다운로드는 권한 정책으로 미실행) → 수동 패턴 검사(누수 없음), CI gitleaks job에 위임. 커밋 대상에 `.env`·node_modules·dist·빌드 산출물 없음 |

**미검증**: GitHub Actions(아래), Docker/Testcontainers 경로, 실제 브라우저 조작, 운영 DB(prod profile, binlog ON 트리거).

**CI**: Draft PR [#1](https://github.com/Hjinsu/ai-studio/pull/1)(main 대상, 사용자 승인, **병합 금지**, 워크플로 트리거 미변경)로 실행. run 45525957400·45526405063은 gitleaks 오탐으로 실패(상세 T02_REPORT §7) → `.gitleaksignore` fingerprint 예외(커밋 e0ae07e, 93bbde3, DECISIONS P-T02-02 승인 대기) → **run 45526843601(93bbde3) 전 job 통과**(Backend Linux unit+Testcontainers integration+image, Windows, Frontend ×2, OpenAPI contract, gitleaks). CI 통합 테스트 개수는 로그에 출력되지 않아 미확인.

**DB 상태**: 3306 작업 DB `ai_studio`는 변경하지 않음(여전히 migration 미적용). 임시 인스턴스(3307, 세션 scratchpad)는 테스트 종료 후 정상 종료. 절차: [docs/guides/LOCAL_TEST_GUIDE.md](docs/guides/LOCAL_TEST_GUIDE.md).

**주의(이 PC)**: vitest는 대문자 드라이브 경로(`C:\...`)에서 실행해야 한다(소문자 `c:\` 작업 경로에서는 모듈 이중 로드로 전 파일 실패). IDE의 Java 오류 표시는 `backend/bin` 오래된 classpath 기준이라 무시(Gradle 빌드 기준).

**다음 작업**: (1) 설계 담당 최종 검토(T02_REPORT, ADR-062, P-T02-02) → 승인 시 T02 DONE·PR 병합 여부 결정 (2) T03은 승인 전 시작 금지.

### T02 — 1차 재개 세션 (2026-11-25, 다른 PC, 당시 IN_PROGRESS)
요구사항: T02_PLAN §2 단계 6 검증·§4 테스트, ADR-026~061, T02_BUDGET_LEDGER §2~§6. T03 미착수. 커밋 없음(사용자 지시 대기). 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`(origin에서 checkout, 4b29e7b 기준).

**환경 (이 PC)**: Windows 11, Gradle JDK 17.0.8 + toolchain JDK 21.0.13, Node 24.21.0, MariaDB 11.8.2 서비스(3306), Docker 없음. 루트 `.env` 존재(필수 변수 3개 설정). 로컬 `ai_studio` DB: 비어 있음(`flyway_schema_history` 없음, collation `utf8mb4_unicode_ci`, 계정 `ai_studio`@localhost ALL on ai_studio). **V1~V5 미적용 — 의도적**: P-T02-01 결정 전 migration을 로컬 DB에 적용하지 않음(적용 후 수정 금지 규칙). 적용은 `./gradlew.bat :backend:bootRun`(local profile) 시 자동.

**통합 테스트 실행 방법 (Docker 없는 PC)**: 작업 DB 서버가 아닌 **임시 MariaDB 인스턴스**를 띄우고 `AI_STUDIO_IT_DB_*`로 지정한다(TestDatabases가 `ai_studio_test_*` DB 생성).
```
"C:/Program Files/MariaDB 11.8/bin/mariadb-install-db.exe" --datadir=<임시폴더>/itdb --port=3307 --password=<임시>
"C:/Program Files/MariaDB 11.8/bin/mariadbd.exe" --defaults-file=<임시폴더>/itdb/my.ini --bind-address=127.0.0.1 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci --default-time-zone=+00:00 --max-connections=300 --console
AI_STUDIO_IT_DB_HOST=127.0.0.1 AI_STUDIO_IT_DB_PORT=3307 AI_STUDIO_IT_DB_USER=root AI_STUDIO_IT_DB_PASSWORD=<임시> ./gradlew.bat :backend:integrationTest
```
(이번 세션은 세션 scratchpad에 생성, 저장소 밖. 사용 후 프로세스 종료·폴더 삭제 가능.)

**ChannelIT 실패 원인 분석 (이전 기록의 2건)**
- 두 건 모두 500이 아니라 **OpenAPI 계약 검증 실패**였다(이전 기록의 "500"은 이 PC에서 재현되지 않음, line 97은 contract matcher).
- (1) `createListGetAndUpdateChannelWithContract`: 검증 라이브러리(openapi-request-validator 3.0.0)가 응답 스키마에 `additionalProperties:false`를 자동 주입하는데 `allOf[PageMeta, {items}]` 하위 스키마마다 주입되어 정상 페이지 응답을 거부. `withResolveCombinators(true)`는 3.1 스펙에서 효과 없음(확인). 1차 조치였던 테스트 헬퍼 `strictAllOf`는 중첩 위반을 숨기는 것으로 확인되어 **제거**(P-T02-01). → 명세 평탄화로 해결, 통과.
- (2) `configRevisionsAreImmutableHistoryWithOptimisticBase`: **스펙 자체 모순** — `ChannelConfigSettings`가 `additionalProperties:false`를 명시하므로 `ChannelConfig`(allOf) 응답이 어떤 경우에도 유효할 수 없음. DECISIONS.md `P-T02-01` 제안 → **승인(ADR-062)** → `openapi.yaml` 평탄화(allOf 10곳 제거, JSON 구조 불변)로 해결, 통과. 상세는 DECISIONS P-T02-01.

**추가한 테스트 (예산 DB 통합, 이전 기록 "다음 작업 2")**
- `budget/BudgetFailClosedIT`(1): 지출 차단 기본값→POLICY_VIOLATION, SYSTEM 정책 없음/DAY만/MONTH 비활성, 환율 없음, PAUSED 채널→PREREQUISITE_NOT_MET, 0원 정책→BUDGET_EXCEEDED, SYSTEM 한도 초과·한도와 같은 금액 허용, 정합성 ERROR 시 차단·OK 후 해제, 지출 차단 중 정산 허용. 실패 시 예약·원장·bucket 기록 0건 확인.
- `budget/BudgetLedgerIT`(6): 설계 §2 USD 계산 예시 전체 단계 값 일치(PROVISIONAL 증감, CONFIRM 초과+BUDGET_OVERRUN audit, KRW 전용 ADJUSTMENT 후 C₀ 유지, 원본 REFUND), 연결 bucket 동일 반영, 불변식 위반 422 무기록, SETTLED→PROVISIONAL 409 / PROVISIONAL 없는 CONFIRM, RELEASE, 지연 과금(근거 필수, LATE_CHARGE), 비용 인식 후 RELEASE 거부, RECONCILIATION_REQUIRED 자동 해제 없음 / request_key 대소문자 구분·같은 키 다른 내용 409·event_key 멱등 / 20스레드 동시 예약(한도 1000, 100원씩) → 정확히 10건 성공·bucket 1개·reserved=1000, 같은 key 10스레드 동시 → 1건 / SYSTEM 사용량 중복 합산 없음, 대시보드·사용량·정책 목록·정합성 검사·환율 등록 API 계약 / cost_log·budget_reservation_bucket·exchange_rate·budget_policy_revision·budget_control 트리거 차단. 각 테스트 끝 정합성 검사 0건.
- 변이 확인: `BudgetReservationService` 한도 검사를 임시 무력화 → 2건 실패 검출 → `git checkout`으로 원복(diff 없음 확인).

**변경 파일**: `backend/src/test/java/com/aistudio/ApiTestSupport.java`(수정), `backend/src/test/java/com/aistudio/budget/{BudgetFailClosedIT,BudgetLedgerIT}.java`(신규), `DECISIONS.md`(P-T02-01 제안), `STATE.md`. main 코드·migration·openapi.yaml 변경 없음.

**실행한 테스트 (실제 결과)**
| 명령 | 결과 |
|---|---|
| `./gradlew.bat :backend:test` | 121 tests, 실패 0, skip 0 |
| `./gradlew.bat :backend:integrationTest`(임시 MariaDB 11.8.2 :3307) | **51 tests, 실패 1**(ChannelIT config revision — P-T02-01 대기), 나머지 50 통과: ActuatorExposureIT 13, DatabaseBootstrapIT 8, DatabaseUpgradeIT 1, ErrorDispatchIT 5, AdminBootstrapIT 2, AuthIT 9, ChannelIT 5/6, BudgetFailClosedIT 1, BudgetLedgerIT 6 |
- 미실행: CI(push 안 함), Docker/Testcontainers 경로, 프론트(변경 없음), gitleaks(미설치 — 변경은 테스트 코드·문서뿐이며 비밀값 없음, 임시 DB 비밀번호는 저장소 밖).

**미해결**
- P-T02-01 승인 필요 → 승인 시 openapi.yaml 대안 A 반영 후 ChannelIT 재실행.
- 예산 미검증 항목(T02_PLAN §4): 정책 revision/시간대 변경·채널 일시정지·지출 차단과 예약의 **동시** 실행, 예약 도중 강제 예외 rollback, 운영 앱 계정 권한(`prod-app-grants.sql`) 적용 계정 검증, 환율 만료 시 fallback 없음(DB IT), 채널 설정 revision 동시 생성.
- IDE(Eclipse/VS Code Java) 진단은 `backend/bin`의 오래된 classpath 기준이라 오류 표시가 실제 Gradle 빌드와 다름(`backend/bin/` untracked, 커밋 금지).

**다음 작업**: (1) P-T02-01 결정 (2) 위 예산 미검증 항목 IT 추가 (3) 서버 검증 완료 후 T02_PLAN §2 단계 7(관리자 UI)·8(타입 생성 도구). T03 시작 금지.

### T02 — WIP 인수인계 (2026-11-25 02:54 KST, IN_PROGRESS 유지)
이번 작업은 인수인계·저장만 수행했으며 신규 기능 코드는 수정하지 않았다. T02는 DONE이 아니고 T03는 시작하지 않았다.

**구현 확인 (코드 존재 기준, 완료 승인 아님)**
- 단계 2~3: Flyway V3~V5 신규 파일, `MigrationConventionTest` 해시 등록, Clock/lock retry/page response/audit 공통 코드가 작업 트리에 있다. V1·V2 파일은 변경되지 않았다.
- 단계 4: 관리자 CLI, 세션/CSRF 인증, rate limit, credential version 검증, audit 코드와 단위·통합 테스트가 있다.
- 단계 5: 채널 CRUD·상태 전이·설정 revision 및 `ChannelIT`가 있다.
- 단계 6: 환율·정책·예산 bucket/예약·정산·원장·정합성 검사·대시보드 백엔드와 계산 단위 테스트가 있다. 다만 예산 DB 통합 테스트는 아직 없다.
- 검증/기반: API 오류 처리 변경, OpenAPI 검증 테스트 의존성 및 일부 인증·채널 contract test가 있다.

**미완료 / 미검증**
- T02_PLAN.md §2 단계 7~10: 관리자 UI, 격리된 OpenAPI 타입 생성 도구·CI 검사, 전체 계약 검증, T02_REPORT 및 전체 CI 결과가 없다. 프론트엔드 파일은 이번 작업에서 변경되지 않았다.
- `ChannelIT` 최근 결과 XML(2026-11-25 02:48): 6건 중 4건 통과, 2건 실패. `configRevisionsAreImmutableHistoryWithOptimisticBase`는 응답의 `channelId`가 OpenAPI schema에 정의되지 않았다는 contract 오류. `createListGetAndUpdateChannelWithContract`는 요청 중 예상 200 대신 500. 원인 해결 전 통합 테스트 통과로 간주하지 않는다.
- 이외 AuthIT/AdminBootstrapIT/DatabaseUpgradeIT 및 예산 동시성·정산 DB 통합 검증은 성공 결과가 확인되지 않았다. 통합 테스트용 격리 DB 환경변수는 설정되지 않았고 Docker 명령도 사용할 수 없어 이번 인수인계 중 통합 테스트를 재실행하지 않았다.
- 현재 로컬 `ai_studio` DB는 MariaDB 11.8.2, Flyway V1·V2 성공 이력만 확인했다(읽기 전용 JDBC 조회). V3~V5는 미적용이며 이후에도 migration 파일을 수정하지 않는다.
- 실행한 명령: `.\gradlew.bat :backend:test` — 121 tests, 실패 0, 오류 0, skipped 0; 컴파일 포함 BUILD SUCCESSFUL.
- 민감정보/산출물: gitleaks는 설치되지 않았다. 변경 소스의 비밀키·일반적인 credential 패턴 수동 검색 결과 없음. 스테이징 목록 재확인에서도 `.env`, 인증 파일, build/node_modules/dist, 로그, 미디어, DB dump는 없었다. `.env`와 build/node_modules/dist는 Git ignore 처리되어 있다.

**현재 변경 및 저장**
- 기반 브랜치 `main`은 원격 `origin/main`보다 1개 커밋 앞서 있고, 그 커밋 `e1cef2c`는 T02 설계 기준선이다. 기존 작업 변경은 backend T02 코드·테스트와 V3~V5 migration이다.
- 변경은 별도 브랜치 `wip/t02-implementation-checkpoint-2026-11-25`에 커밋 메시지 `wip: checkpoint T02 implementation`으로 저장·푸시한다. `.env`, 인증 비밀정보, 빌드 산출물은 포함하지 않는다.
- 다음 작업자는 해당 브랜치를 checkout/pull하고 이 기록 및 `docs/plans/T02_PLAN.md`를 먼저 확인한다.

**정확한 다음 작업**
1. `ChannelIT`의 위 두 실패를 재현해 원인을 해결한다(contract schema의 `channelId` 누락 여부와 500 발생 경로를 각각 확인).
2. 예산 정책·예약 동시성·정산/원장·정합성 검사를 격리 MariaDB 기반 통합 테스트로 추가하고 `.\gradlew.bat :backend:integrationTest`를 실행한다. 로컬 `ai_studio`를 테스트 대상으로 사용하지 않는다.
3. 인증·관리자 생성·V2→V5 upgrade 통합 테스트 결과까지 확인한 뒤, 서버 측 검증이 끝나면 T02_PLAN.md §2 단계 7(관리자 UI)을 이어간다. 그 전에는 T03를 시작하지 않는다.

### T02 — IN_PROGRESS (2026-11-25 구현 승인, ADR-039~061 반영)
설계 기준선: ADR-026~061, docs/design/*, docs/api/openapi.yaml, docs/plans/T02_PLAN.md, infra/mariadb/prod-app-grants.sql.

#### (이전 기록) 설계 단계
요구사항: ADR-026~038(D-01~D-13). 이번 단계는 설계 문서·DDL 초안·OpenAPI 초안만 작성, **도메인 코드 없음**. 커밋 없음.
- 산출물: docs/design/{T02_DATA_MODEL.md, T02_DDL_DRAFT.sql, T02_BUDGET_LEDGER.md, T02_AUTH.md}, docs/api/openapi.yaml, docs/plans/T02_PLAN.md(갱신), docs/reports/T02_DESIGN_REPORT.md, DECISIONS.md(ADR-026~038), docs/00, 03, 04, 09, 12, 13, 14, 16
- 검증(scratchpad, 저장소에 코드·도구 추가 없음):
  - DDL: 별도 임시 MariaDB 11.8.2(127.0.0.1:3307, scratch datadir, 기존 서버·데이터 미사용, 검증 후 삭제)에 V1→V2→초안 적용, 제약·트리거 42/42, 권한 분리(앱 계정 원장 UPDATE/DELETE 거부) 확인
  - OpenAPI: `@redocly/cli@2.83.0 lint` 유효(경고 1, 의도)
  - 계약 검증: `openapi-request-validator(-mockmvc) 3.0.0` + Boot 4.1.3 BOM 스파이크 17/17
  - 타입 생성: `openapi-typescript@7.13.0`은 frontend 설치 시 ERESOLVE(peer TS ^5), TS 5.9.3 격리 생성물은 TS 6.0.3 strict 통과
- 미해결: T2-Q1~Q14(T02_PLAN 5절), 동시성·Flyway 경유 적용은 구현 시 검증
- 다음 작업: T02_PLAN 2절 순서로 구현

### T01 — DONE (2026-11-25 설계 담당 승인)
완료 승인 근거: 커밋 55e33ec, GitHub Actions run 45440907314 전 job 성공. 유지되는 한계: CI job 로그를 독립적으로 확인하지 못함(인증 필요), 실제 운영 DB(prod profile·migration 계정 분리·binlog ON 트리거) 미검증.
요구사항: ADR-006~014, ADR-023(loopback), ADR-024, ADR-025, docs/02, 03, 04, 13, 14, 15. 설계 검토용 보고서: [docs/reports/T01_REPORT.md](docs/reports/T01_REPORT.md).

**커밋·CI**
- 커밋 `55e33ecb94b00e5384b10e04f7f88a7b918fb0fe` "feat: complete T01 foundation and design hardening"(82개 파일, `gradlew` 100755) → origin/main 푸시.
- 커밋 전 검사: 스테이징 대상에 `.env`·build·node_modules·dist·로그·영상·덤프 없음, `.env` 비밀번호 문자열 미포함, `gitleaks git --staged` 누수 없음.
- GitHub Actions run [45440907314](https://github.com/Hjinsu/ai-studio/actions/runs/45440907314) (2026-11-24T16:45:55Z~16:48:51Z UTC) **전 job success**:
  | job | 단계 결과 |
  |---|---|
  | Backend (Linux, unit + integration) | Unit tests ✓, Integration tests(Testcontainers MariaDB) ✓ 23초, Build boot jar ✓, Validate Docker Compose ✓, Build backend image ✓ |
  | Backend (Windows, unit only) | `gradlew.bat :backend:test :backend:bootJar` ✓ |
  | Frontend (ubuntu-24.04) / (windows-2025) | npm ci, lint, typecheck, test, build ✓ |
  | Secret scan (gitleaks) | ✓ |
- 한계: job 로그는 GitHub 인증이 필요해(API 403) 테스트별 개수를 직접 확인하지 못함. 통합 단계 성공은 Gradle 9가 테스트 미발견 시 실패하는 동작과 단계 소요(23초)로 DatabaseBootstrapIT·DatabaseUpgradeIT 실행을 간접 확인. 필요 시 Actions 화면에서 로그 확인.
- 커밋 이후 변경(미커밋): 이 CI 결과 기록, T02 설계 문서 일체.

**3차 변경 (설계 확정 반영)**
- 오류 처리(ADR-012, 024): ErrorCode 19개, `ErrorCode.fromStatus`(미분류 상태 유지), `ErrorResponses`(JSON Content-Type 고정 → 406도 본문), `ApiErrorController`(`/error`: 필터·sendError 오류 공통 형식), `GlobalExceptionHandler` 수정
- DB 보호(ADR-024, 025): `DatabaseGuardConfiguration`(구 LocalDatabaseGuardConfiguration, 모든 profile에서 Flyway 직전 검사), `RequiredDatabaseEnvironmentPostProcessor`(local/docker/prod, prod는 migration 계정 필수, expected-database 미설정 시 중단), profile별 expected-database(local/docker/prod=`ai_studio`, test=`ai_studio_test`), prod Flyway 계정 분리 설정
- `V2__fix_database_collation.sql`(ADR-014), local `server.address=127.0.0.1`(ADR-023), Vite 프록시 기본값 `127.0.0.1:8080`, Compose `--collation-server=utf8mb4_unicode_ci`
- CI: Windows backend job을 `shell: cmd` + `gradlew.bat`으로 변경(pwsh에서 POSIX `gradlew` 실행 불가 문제)
- 테스트 추가: ErrorDispatchTest, DatabaseGuardConfigurationTest, MigrationConventionTest, DatabaseUpgradeIT, TestDatabases, DatabaseGuardTestAccess. 수정: ErrorCodeTest, GlobalExceptionHandlerTest, RequiredDatabaseEnvironmentPostProcessorTest, DatabaseBootstrapIT
- 문서: DECISIONS.md(ADR-012 승인, 014~025, 이슈 상태표), docs/00, 02, 03, 04, 06, 07, 08, 09, 10, 12, 13, 14, README, .env.example, docs/plans/T02_PLAN.md(신규), docs/reports/T01_REPORT.md

**전체 변경 파일 (T01 누적)**
- 빌드: settings.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*, gradlew, gradlew.bat, backend/build.gradle.kts
- 백엔드 main: com/aistudio/AiStudioApplication.java, shared/api/{ErrorCode,ErrorDetail,ErrorResponse,ErrorResponses,ApiException,ApiErrorController,TraceIdFilter,GlobalExceptionHandler}.java, shared/db/{DatabaseTarget,RequiredDatabaseEnvironmentPostProcessor,DatabaseGuardConfiguration}.java, resources/META-INF/spring.factories, resources/application{,-local,-test,-docker,-prod}.yml, resources/db/migration/V1__initial_baseline.sql, V2__fix_database_collation.sql
- 백엔드 test: ActuatorExposureTest, ErrorDispatchTest, DatabaseBootstrapIT, DatabaseUpgradeIT, TestDatabases, shared/api/{ErrorCodeTest,TraceIdFilterTest,GlobalExceptionHandlerTest}, shared/db/{DatabaseTargetTest,RequiredDatabaseEnvironmentPostProcessorTest,DatabaseGuardConfigurationTest,MigrationConventionTest,DatabaseGuardTestAccess}
- 프론트: frontend/{package.json, package-lock.json, .nvmrc, tsconfig.json, tsconfig.node.json, vite.config.ts, eslint.config.js, index.html}, src/{main.tsx, App.tsx, api/client.ts, api/health.ts, pages/HealthPage.tsx, pages/HealthPage.test.tsx, test/setup.ts}
- 인프라/CI: infra/docker-compose.yml, infra/backend.Dockerfile, infra/mariadb/create-local-database.sql, .dockerignore, .github/workflows/ci.yml, .gitignore, .gitattributes
- 로컬 전용(Git 제외): `.env`

**실행한 테스트 (2026-11-25, Windows 10, Gradle JDK 17 + toolchain JDK 21.0.13, Node 24.19.0, 로컬 MariaDB 11.8.2)**
| 명령 | 결과 |
|---|---|
| `./gradlew :backend:test --rerun-tasks` (커밋 직전) | 성공. 단위 103개 실패 0, skip 0 (ActuatorExposure 13, ErrorDispatch 5, ErrorCode 18, GlobalExceptionHandler 35, TraceIdFilter 2, DatabaseGuardConfiguration 6, DatabaseTarget 11, MigrationConvention 4, RequiredDatabaseEnvironmentPostProcessor 9). deprecation 경고 없음 |
| MigrationConventionTest 변이 확인(임시 V99 CHARSET-only 파일 추가 후 실행, 즉시 삭제) | 규칙 위반·미등록 검출로 실패 확인 → 삭제 후 통과. 이 과정에서 `classpath:` 스캔 누락 버그 발견·수정(`classpath*:`) |
| 로컬 V1 DB 업그레이드: `./gradlew :backend:bootRun`(local) | `Verified connected database 'ai_studio'` → `Current version: 1` → `Migrating ... "2 - fix database collation"` → `now at version v2`. DB 기본 collation `utf8mb4_unicode_ci` 확인, history v1·v2 success |
| 재기동 | `Current version: 2`, `up to date`, health UP |
| DDL 규칙 확인(로컬 임시 테이블) | CHARSET/COLLATE 생략 → unicode_ci, `DEFAULT CHARSET=utf8mb4`만 → **uca1400**, COLLATE 명시 → unicode_ci |
| loopback 바인딩 | LISTEN `127.0.0.1:8080`만, LAN IP 접속 실패(000) 확인 |
| Vite 프록시 E2E(dev server 127.0.0.1:5173 → backend) | `/actuator/health` 200 UP, `/api/v1/nope` 404 |
| `cd frontend && npm run lint / typecheck / test / build` | 모두 exit 0, vitest 4/4 |
| `actionlint` 1.7.12 | 오류 없음 |
| `gitleaks dir`(커밋 대상) / `gitleaks git` v8.30.1 | 누수 없음. `.env` ignored 확인 |
| `git ls-files -s gradlew` | `100755`(staged) |

**로컬 미실행 → CI에서 실행된 테스트**
- `./gradlew :backend:integrationTest` — 로컬은 Docker 없음으로 실패. **CI Linux job에서 성공**(DatabaseBootstrapIT, DatabaseUpgradeIT; 테스트별 개수는 로그 인증 필요로 미확인).
- `docker build` / `docker compose config` — 로컬 미실행, **CI에서 성공**. Compose 실제 기동(up)은 미실행.
- GitHub Actions — run 45440907314 전 job 성공(Windows `gradlew.bat` 포함).
- prod profile(migration 계정 분리) — 단위 테스트만, 실제 운영 DB 미검증. 검증 범위: (1) 기동 전 URL의 DB 이름(두 계정 공통 URL) (2) Flyway 직전 migration 계정 `SELECT DATABASE()` (3) 계정이 분리된 경우 애플리케이션 계정 `SELECT DATABASE()`도 Flyway 전에 확인(DatabaseGuardConfigurationTest 6).

**미해결 / 주의**
- Flyway 12.4.0 "MariaDB 11.8 미검증(11.7까지)" 경고 — 조건부 허용(ADR-014), 로그에 그대로 출력.
- `flyway_schema_history` 테이블 collation: 로컬 unicode_ci. CI 컨테이너도 collation-server=unicode_ci로 맞춤(통합 테스트에서 미검증).
- 사용자 로컬 `.env`의 `AI_STUDIO_BACKEND_URL`은 이전 값(localhost) — Vite는 OS 환경변수만 읽으므로 영향 없음.
- Actuator health 하위 경로는 빈 본문 404(ADR-024 별도 계약).

**다음 작업**: 없음(T01 완료). T02 진행.

**필요 환경변수 (ADR-013, `AI_STUDIO_` 접두사만)**: 필수(local/docker/prod) `AI_STUDIO_DB_URL`, `AI_STUDIO_DB_USER`, `AI_STUDIO_DB_PASSWORD`; prod 추가 필수 `AI_STUDIO_DB_MIGRATION_USER`, `AI_STUDIO_DB_MIGRATION_PASSWORD`; 선택 `AI_STUDIO_BACKEND_URL`(기본 `http://127.0.0.1:8080`). 선행 조건: 루트 `.env`, JDK 17+, 인터넷(최초 JDK 21/의존성), Node 24 LTS, MariaDB 11.8 + 전용 DB/계정, Docker(선택).

## V2-00 기준 전환 — 2026-11-26 KST

- **DONE · ChatGPT APPROVE 및 사용자 승인.** v2/integration 분기 원점 `dab7013c4557ba87d4440dfc86417344bb2c4e1d`. 위 T03 상태는 과거 기록이다. 원격 C/D WIP와 기존 로컬 변경을 보존한다.
- 변경 범위는 루트 개발 규칙·상태·결정·목차·협업 가이드, `docs/v2/` 및 [회사 PC 인수인계](docs/NEXT_SESSION.md)다. 문서 공백·링크·비밀 패턴 검사를 수행하고 V1~V6 및 제품 경로가 기준 SHA와 일치함을 확인했다. 제품 테스트·DB 접근·Migration 적용·CI 현행 결과 확인은 미실행이다.
- 다음 배정은 V2-01이며 별도 착수 승인 후 진행한다. 첫 경계는 소재 전 제작 요청 ID와 한도 귀속이다. [작업 배정](TASKS.md)

## V2-Q01 — 콘텐츠 품질·수익화 문서 반영 (2026-11-26 KST)

- V2-00 DONE 유지. [품질·수익화 요구사항](docs/v2/05_CONTENT_QUALITY_AND_MONETIZATION.md)과 제품·로드맵·인수인계 문서 갱신. 제품 테스트·DB 접속·Migration 적용 미수행.
- 다음 작업은 V2-01 공통 계약 설계 검토 및 제품 착수 승인이다. FFmpeg·FFprobe 준비는 V2-04에 배정했다.

## V2-01 — 공통 계약 인수인계

- 상태 IN_PROGRESS, 사용자 착수 승인. 공통 기준은 `v2/common`의 공통 계약 커밋이며 `v2/product`와 `v2/safety`는 같은 SHA에서 시작한다.
- 변경 범위는 V9의 세 테이블과 권한, Java 공통 포트·상태·효과 키, 제작 요청 생성·조회·취소 API, OpenAPI·생성 타입 및 계약 테스트다. V1~V6 원본과 C/D WIP는 보존한다.
- 다음 작업은 Claude의 실행·lease·취소·복구와 Codex의 소재 연결·비용 포트·정산 구현이다. FAKE로 결합 검증한 뒤 독립 리뷰와 사용자 검토를 진행한다. [계약 및 착수 지시](docs/v2/V2-01_IMPLEMENTATION_PLAN.md)
- JDK 21.0.13에서 `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 성공. 단위 151/151, 격리 MariaDB 11.8.2 통합 154/154. V9은 격리 DB에서만 적용했으며 새 DB 및 기존 기준 업그레이드를 검증했다. OpenAPI lint(기존 경고 2건), 생성 타입 일치, diff 및 일반 비밀정보 패턴 검사 통과.
- V7 이력 DB 이관과 실행·비용 결합 검증은 보류다. 사용자 DB 적용 이력은 조회하지 않았다.

## V2-01 — 최종 승인 및 종료

- 2026-11-26 KST · **DONE**. ChatGPT 최종 설계·구현 검토, Claude 실행기 독립 리뷰, 별도 Codex 세션의 비용 엔진 리뷰(`6675f86a8a996ff314b7316436e489d2d2445502`) 및 사용자 종료·병합 승인을 확인했다.
- [PR #4](https://github.com/Hjinsu/ai-studio/pull/4)를 v2/integration에 기존 이력을 보존하는 merge commit `37308fbd65d7f3a4a3bf61b454faea629f53f1ca`로 병합했다. 검증 SHA `23626c609dd7526666f6d9b0e8c035babbcf433c`와 병합 커밋의 전체 Git 트리가 일치한다.
- [CI Run 45737242958](https://github.com/Hjinsu/ai-studio/actions/runs/45737242958) 7/7 성공. 격리 전체 백엔드 단위 165건·통합 200건, bootJar, binlog ON 핵심 18건, 실제 비용·선정 포트 FAKE 11단계, 효과 8건·확정 KRW 8 확인. 프론트 단위 73건·Edge E2E 10건, OpenAPI 생성 타입 확인. OpenAPI 경고 2건은 공통 기준부터 유지된 항목이다. [실행기 보고서](docs/reports/V2-01_EXECUTION_REPORT.md) · [비용 검증](docs/v2/V2-01_SAFETY_HANDOFF.md)
- V9 적용 검증은 격리 DB에서 수행했다. 이번 종료 세션은 Git·문서 검증만 수행했으며 사용자 DB에는 접속하거나 V9을 적용하지 않았다. 실행기 기본값은 비활성이다. 실제 유료 호출과 YouTube 게시는 각각 별도 승인 범위다.
- 다음 배정은 **V2-02 소재 자동 발굴 및 선정**이다. 기술 선행 조건은 충족했으며 사용자 착수 승인 대기 상태다.

## V2-02 — 설계 및 공통 계약 착수

- ChatGPT APPROVE WITH MINOR FIXES 및 사용자 착수 승인. `91c3e9ad1394b1c9b122231a60b8acc5a8d873b8`에서 `v2/02-common`을 시작했다. [구현 계획과 R01~R07](docs/v2/V2-02_IMPLEMENTATION_PLAN.md)
- V10은 제작 요청별 소재 묶음 테이블 1개를 추가한다. 신규 SHORTS 요청은 스냅샷을 저장하고 제품 실행기가 준비될 때까지 대기한다. 기존 요청은 이전 경로를 실행한다.
- 다음 작업은 공통 계약 HEAD에서 `v2/02-product`와 `v2/02-safety`의 담당 구현 및 결합 검증이다.

- 격리 MariaDB 11.8.2에서 `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest --rerun-tasks --no-build-cache`를 실행했다. 단위 165건과 통합 202건이 통과했다. V10 설치·업그레이드, V9 요청 멱등 재생, 기존 실행기의 새 파이프라인 제외, 평가 조회·수동 선택의 차단 및 재생을 확인했다.
- OpenAPI lint와 생성 타입 일치가 통과했다. 공통 기준부터 존재한 경고 2건을 확인했다. V1~V9 원본은 동일하며 사용자 DB에 V10을 적용하지 않았다.
- 생성·평가·선정·UI, 응답과 영수증 장애 복구, 실제 동시 SELECT와 잠금 순서의 V2-02 결합 검증은 역할별 구현 후 진행한다.

## V2 콘텐츠 제작·품질 정책 개정 (2026-11-26 KST)

- 정책 문서 반영 완료. 변경 파일은 `docs/v2/`의 `01_PRODUCT.md`, `03_ROADMAP.md`, `05_CONTENT_QUALITY_AND_MONETIZATION.md`, `V2-02_IMPLEMENTATION_PLAN.md` 및 루트 `DECISIONS.md`, `STATE.md`다.
- 차이 분석 기준은 임시 결합 SHA `463c3c0dd56911b69dfd6e1f475e92726391ff38`이다. 현재 `SIMILARITY_UNCERTAIN`과 `UNSUPPORTED_ASSERTION`은 부적격 처리를 하며 적격 후보가 없으면 추가 발굴 없이 실패한다. 새 검수 분류, 미검증 주장 인계, 최종 점수 구간은 정책에만 반영했다.
- Claude는 결합 수정 커밋 `a820a42`를 `v2/02-product-fix`에 제출했다. 제품 보고서 기준 백엔드 단위 210건·격리 통합 222건, 프론트 단위 88건이 통과했다. Codex 결합 검증 브랜치는 `463c3c0`이며 수정 커밋의 반영과 독립 검증이 남아 있다.
- 종료 전 최소 수정 범위는 실질 중복 차단 유지, 독립 가치가 있는 일부 유사 소재 감점, 동일 분야 새 질문·관점 허용, 불확실 후보의 검수 필요 분류 및 자동 선정 제외, UI의 차단·감점·검수 필요와 근거 표시다. 기존 평가 JSON·비교 근거·묶음 버전을 우선 쓰고 기존 결합 검증에 해당 회귀를 추가한다. 이 커밋은 검증 브랜치에 이식할 문서 변경만 담는다.
- 미검증 주장 인계는 V2-03, 최종 품질 점수 및 부분 개선은 V2-05에 배정한다. 재발굴과 비용·실행 안전성·기존 Migration 변경은 제외한다.
- 문서 변경 범위와 공백은 `git diff --name-only`, `git diff --check`로 확인했다. 코드 테스트, 사용자 DB 접속, Migration 적용, 실제 유료 API 호출은 수행하지 않았다.

## V2-02 — Final validation candidate

- `v2/review-v2-02` fast-forwarded to Claude's `a820a42` and includes the policy documentation commit as `991aaec`.
- Existing evaluation JSON stores the comparison reasons. Substantive duplicates remain blocked, independently valuable overlap reduces originality, and uncertain overlap is shown as review required. AUTO excludes uncertain candidates. MANUAL requires confirmation tied to the current batch token and rechecks for new reasons before linking.
- The isolated MariaDB 11.8.2 instance at loopback 3323 used binlog ON. The complete backend rerun passed 210 unit and 222 integration tests and bootJar after the manual confirmation regression was extended and the mode read was moved inside the fenced transaction.
- Frontend typecheck, lint, build, The final Vitest run passed 89 tests, including review-only batches and explicit confirmation. The 13-test Edge E2E suite passed on the preceding candidate. Live Edge used actual frontend/backend and FAKE effects against a separate database on the same isolated instance. AUTO 8, MANUAL 5 and 10, reload, history, cancellation, failure, and light/dark/mobile passed. The policy badge and confirmation display case used seeded evaluation fixtures read through the real API.
- OpenAPI lint and generated type check passed with two pre-existing lint warnings. V1–V11 were applied to isolated databases only and match the safety baseline. The next step is independent review at the final validation commit and final user approval.

## V2 후속 설계 제안 문서 — PROPOSED

- 문서 전용 브랜치 `docs/v2-provider-discovery`, 기준 `c4832b7eca2cbf89893bce92fa2b980263cf695e`. [공급자 및 제작 원가](docs/v2/06_AI_PROVIDER_AND_PRICING.md)와 [소재 발굴 및 데이터 API](docs/v2/07_TOPIC_DISCOVERY_AND_API.md)를 추가하고 README·제품·기술·로드맵·품질 문서에 연결했다.
- V2-05·07·08 후속 범위에 기존 비용 보호와 데이터 이용 조건을 적용한다. V2-03은 승인된 FAKE/FIXTURE 범위를 따른다. API 가격·권리는 해당 단계에서 활성화 직전에 재검증한다.
- Markdown 링크·Git 공백·비밀정보 패턴을 정적으로 검사했다. 코드 테스트, DB 변경, migration 적용 또는 실제 API 호출은 수행하지 않았다. 다음 작업은 문서 전용 커밋·푸시와 제안 검토다.
