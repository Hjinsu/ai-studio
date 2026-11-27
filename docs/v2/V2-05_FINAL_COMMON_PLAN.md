# V2-05 공통 계약 구현 계획

**PROPOSED** · [최종 설계](V2-05_FINAL_DESIGN.md). 승인 후 Codex가 공통 브랜치에서 계약을 고정한다.

| 범위 | 저장소 및 트랜잭션 계약 |
|---|---|
| 실행과 프로필 | 요청 생성·snapshot·planner가 pipeline version별 동일 순서 정의 사용. 기존 `ProductionStage` enum의 전역 순서 변경을 피한다. `MediaProfile` 버전에 채널 설정·공급자 SKU/가격·언어/오디오·자막 정책을 동결한다. OFF의 SUBTITLE에는 산출물 없이 검증된 생략 결과를 기록하고 구 프로필 QC 계약을 유지한다. |
| Worker I/O | `requestScript/draftScript/resultScript`에 schemaVersion·jobId·request/step·attempt/epoch·입력/초안 해시를 연결한다. DB의 발행 의도와 현재 lease/CAS를 권위로 두고, 표준화된 파일을 임시 쓰기·flush·같은 볼륨 원자 이동으로 공개한다. 수집 시 소유권·경로·크기·해시·스키마·선정 지문 확인. 동일 결과 중복 수집은 기존 적용 반환, 상충·stale는 격리. 사용자가 CLI를 실행하며 서버는 토큰·키를 읽지 않는다. 외부 검색 데이터는 제어 입력과 분리한다. |
| 상태·복구 | 승인되지 않은 무인 실행 없이 사용자 발행·수집을 제공한다. WAIT(선행 작업/구독 제한/인증 확인)는 명시 사유와 기존 due/park 기반으로 관리한다. 현재 owner/epoch만 채택한다. 재시작 시 영속 의도와 기존 파일부터 대조한다. 스크립트 자동 재호출 없음. 승인된 일회성 검증의 구독 CLI 내부 정책은 미디어 API 재시도 금지에 영향을 주지 않는다. |
| DB 확장 후보 | V10 `production_discovery`, V12 `production_stage_result`, V13 media item/asset 및 기존 cost operation을 재사용한다. V13 장면 kind/role과 pipeline trigger를 확장하는 다음 migration 후보 V14에 SOURCE_VIDEO/NORMALIZED_VIDEO 및 필요한 job 바인딩만 검토한다. 유료 operation에는 namespace+job ID의 유일·불변 연결과 조회 예정 시각을 보존한다. 구독 작업에 가짜 비용 영수증을 부여하지 않는다. job/attempt 내구성을 기존 저장소가 만족하는지 확인하고 필요한 최소 변경을 승인받는다. 음원 등록은 로컬 카탈로그, 사용 이력은 기존 manifest로 시작한다. |
| 비용·파일 | 예약과 바인딩 → 현재 lease/기간/한도 확인 후 단일 permit → 트랜잭션 외부 호출 → job ID/증거 영속화 → 제한 다운로드·hash·publish → 단일 settle/적용 순으로 계약을 고정한다. 가격은 승인 SKU·단위·통화·환율·최대 사용량을 비교하고 변경·불명 시 차단. fal은 동일 ID 상태 조회만 허용하고 제출 응답 URL을 승인 호스트·경로 검사 후 사용한다. |
| UNKNOWN 운영 | job ID 유실 시 예약과 불명 상태 유지. 권한·감사 있는 관리 경로로 확인된 기존 job과 청구를 연결하고 기존 receipt로 한 번 정산한다. 미접수와 무과금이 모두 입증된 경우만 종료하며 증거 없는 재전송·재생성·예약 반환을 금지한다. |
| API/타입 | OpenAPI와 생성 타입의 변경은 Codex 소유. 기존 인증·소유권·CSRF·오류·문자열 금액·GET/HEAD/Range 보존. 사용자 실행 파일 발행·수집, 제한된 오디오 큐 검수와 WAIT 사유만 추가 제안. 경로·토큰·서명 URL은 노출하지 않는다. |

핵심 회귀는 기존/신규 프로필, SELECT 및 지문, 요청/일/월 경합과 원장 한도, B01 미발송/기간 만료/파일 손상, 전송 직후 불명과 job ID 후 재시작, 늦은 결과·stale lease·중복 정산, B02 마지막 QC 확정 전 종료와 해시 손상이다. 초기에는 모의 공급자 및 격리 MariaDB/로컬 파일로 검증한다. Migration 파일과 DB 적용은 이번 설계 작업에 포함하지 않는다. [기존 공통 상세](V2-05_REPOSITORY_DESIGN.md)
