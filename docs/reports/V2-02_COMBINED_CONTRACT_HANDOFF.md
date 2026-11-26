# V2-02 공통 계약 결합 · Claude 수정 지시서

## 기준과 작업 경로

- 공통 `0dddd5f390a77b33a19f2091ff438a2fbff223d2`, 제품 `4d40bf7498ed9ae1255b29282fc65c144e601e62`, 안전 `ed98838912a008b12c0d27df4e91d939ae487636`.
- 임시 `v2/review-v2-02` worktree는 `../ai-studio-v2-review-02`다. 기준 두 구현의 merge는 `a7dae657dc2bd31e959810166e85a41fa2089c0c`이다. Claude는 이 문서를 담은 공통 계약 커밋에서 별도 수정 worktree를 만들고 검증 브랜치에 넣을 변경 SHA를 전달한다.
- [제품 보고](V2-02_PRODUCT_REPORT.md) · [안전성 인수인계](../v2/V2-02_SAFETY_HANDOFF.md)

## 반영한 공통 계약

| 지점 | 결합 계약 |
|---|---|
| `SelectionBatch.responseHash` | 채택·적용된 영수증과 대조한 저장 본문의 SHA-256을 반환한다. 실행기는 이 값을 선정 지문에 사용한다. |
| SELECT 호출 시점 | 요청 락·fencing·채널 락을 확보한 상태에서 `PENDING`/재시도 차감 전에 `lockForSelection`과 `invalidateManualChoice`를 호출할 수 있다. 기존 안전 어댑터가 이 경로를 지원하며 PENDING 실DB 테스트가 통과했다. |
| 응답과 비용 | 비용 어댑터가 `DiscoveryInputs.sha256`, `DiscoveryResponseValidator.validate`, `DiscoveryResponseCodec.canonicalJson`을 재사용한다. 응답 검증은 본문·증거 저장 전에 수행하며 정의된 형식 오류는 `EXECUTED_FAILED`로 금액을 확정한다. 일반 공급자 예외는 결과 불명으로 격리한다. |
| 재검사 | 연결 성공은 5인자 `recordSelection`, AUTO 전부 탈락은 `recordExhaustedSelection`, MANUAL 의도 무효화는 `invalidateManualChoice`를 사용한다. 기존 V11 저장소에서 최초 평가와 최신 재검사를 구분한다. |
| 스냅샷 | `providerOptions.discovery.prohibitedTopics`는 문자열 배열이다. 발견 포트가 요청의 고정 설정에서 읽고, 선정 포트는 저장된 언어를 Revision에 적용한다. 명시 언어가 스냅샷과 다르면 거부한다. |
| 조회·선택 API | `GET /api/v1/production-requests?page=0&size=20`은 본인 요청의 최신순 페이지이며 `id`, `channelId`, `channelName`, `selectedTitle`, 상태, KRW 금액, 생성 시각을 제공한다. 제목은 연결 Run의 Revision에서 읽는다. 상세의 각 단계는 nullable `lastErrorCode`를 제공한다. 선택 명령은 `candidateId`, `version`, `batchToken`을 요구한다. OpenAPI와 생성 타입을 갱신했다. |

## Claude 수정 지시

1. `ProductionExecutor.selectInTransaction`에서 재구성한 `Response`는 평가에 사용하고 지문은 `batch.responseHash()`를 사용한다. 성공 시 `fresh`를 5인자 `recordSelection`에 전달한다. AUTO 적격 후보가 없으면 실패 전 같은 트랜잭션에서 `recordExhaustedSelection`을 호출한다. `DiscoverySelections.selection`에서 기존 언어 필드에 `input.language()`를 전달한다.
2. **제품 리뷰에서 확인한 재대기 결함**. `RETRY_WAIT`에서 기존 선택이 무효화되면 의도는 지워지지만 단계가 그대로 남는다. claim, planner, API는 `PENDING`을 대기 조건으로 사용한다. 남은 후보가 있을 때 동일 트랜잭션에서 SELECT를 `PENDING`으로 복귀시키고 retry 시각을 지운다. 누적 시도는 보존한다. `RETRY_WAIT → 무효화 → lease 반납 → 재시작 → 재선택` 회귀를 추가한다.
3. `chooseProductionCandidate`에 `batchToken`을 추가한다. 확인 대화상자를 열 때 후보 ID·버전·토큰을 함께 고정하고, 폴링으로 새 묶음을 받은 경우 사용자에게 다시 선택하게 한다. 단위·Playwright 대역도 동일한 요청/응답과 필수 nullable `lastErrorCode`를 사용한다. 상세에 단계 오류를 표시한다.
4. 공통 `ProductionDiscovery.PROMPT_VERSION`으로 버전 상수를 맞춘다. 기존 `DiscoveryResponseException`은 비용 어댑터에서 이미 형식 오류로 분류한다. 형식 오류 영수증이 적용될 때 실행기가 현재 쓰는 `EFFECT_EXECUTED_FAILED`와 화면 라벨을 함께 확인한다.
5. 제품 `DiscoveryExecutionIT`의 `FakeDiscoveryPort`가 실제 저장 포트 빈과 충돌하고, 대역의 기존 generic DISCOVER 발송은 결합 비용 경계에서 거부된다. 결합 검증에서는 실제 저장·비용·선정 포트에 FAKE 공급자만 사용한다. 장애 주입이 필요한 시나리오는 동일 포트/DB를 유지하고 부수 효과 지점에만 주입한다. 브라우저 확인과 함께 재시작 대기, 차순위, 표현 변경 동시 SELECT, 최초·최신 평가 보존을 포함한다.

## 현재 검증

- 격리 MariaDB 11.8.2 루프백 3319에서 `CombinedDiscoveryIT`의 기본 AUTO 8개 후보, 실제 포트를 경유한 11단계 완료, 정산 효과 8건, 목록의 선정 소재 조회가 통과했다.
- `ProductionRequestApiIT`, `ProductionPortsIT`, `ProductionCostIT`가 통과했다. PENDING 수동 무효화, 응답·영수증 해시 일치, 형식 오류 정산, 목록·상세 계약과 V2-01 회귀를 포함한다.
- 백엔드 단위, OpenAPI lint(기존 경고 2건), 생성 타입 일치 검증을 통과했다. 전체 제품 결합 인수·별도 저장/비용 독립 리뷰·최종 병합은 후속 단계다.
- 사용자 DB에 접속하지 않았다. V1~V11은 기존 파일을 사용했으며 검증 DB에만 적용했다.
