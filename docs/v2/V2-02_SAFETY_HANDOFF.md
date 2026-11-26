# V2-02 저장·안전성 인수인계

2026-11-26 KST · Codex 저장·안전성 IMPLEMENTED · `v2/02-safety` · 공통 기준 `0dddd5f390a77b33a19f2091ff438a2fbff223d2`

## 구현 범위

- `production/discovery/persistence`에서 요청 생성 시 고정한 채널 설정·언어를 읽고, 생성 JSON·SHA-256·공급자 작업·최초 평가·최신 재검사·수동 선택 의도·최종 선정을 연결한다. 카탈로그는 같은 채널의 정확한 Topic 해시, 최근 및 키워드 관련 Content의 현재 Revision을 조회한다.
- `production/cost`에서 기존 비용 예약·발송 허가·원장 정산을 재사용한다. FAKE 응답 본문과 비용 증거를 먼저 함께 커밋하며 저장된 증거로 정산을 재생한다. 형식 오류는 확정 비용과 실패 영수증을 보존하고 불명 결과는 예약과 함께 격리한다. DISCOVER에 새 입력 세대를 만들어 다시 발송하는 경로를 차단한다.
- `production/selection`, `channel`, `topic`에서 요청 락 → 채널 배타 락으로 최신 카탈로그와 연결을 직렬화한다. 신규 발견 경로에서 정확히 같은 정규화 제목의 Topic 재사용을 거부하고 최초 Revision에 스냅샷 언어를 적용한다.
- `production/api`, OpenAPI, 생성 타입에서 선택 명령에 `batchToken`을 추가했다. 후보 순번은 요청마다 반복되므로 토큰은 요청 ID와 묶음 버전에 연결된다. 다른 요청의 토큰과 오래된 버전은 409다. 기존 요청 소유권 검사와 같은 선택의 재전달을 유지한다.
- V11은 기존 `production_discovery`에 마지막 재검사 본문과 시각을 추가한다. 최초 평가를 유지하며 기존 마이그레이션 파일과 운영 계정 테이블 권한은 그대로 적용한다.

## 자체 검증

- `gradlew.bat :backend:test :backend:bootJar :backend:integrationTest` 통과. 단위 165건, 통합 205건, 실패·건너뜀 0건. MariaDB 11.8.2 격리 인스턴스의 루프백 3318 포트와 별도 임시 데이터 폴더를 사용했다. V11의 빈 DB 설치·기존 데이터 업그레이드와 V2-01 실제 포트 회귀를 포함했다. 사용자 DB 접근 및 마이그레이션 적용은 수행하지 않았다.
- `ProductionRequestApiIT`에서 다른 요청 토큰·오래된 선택 거부, 동일 선택 재전달, 수동 재검사와 취소 경합을 검증했다. `ProductionPortsIT`에서 설정 변경 후 스냅샷 보존, 증거 저장 후 정산 장애·재생, 형식 오류 비용, 결과 불명 예약, 채널별 동시 선정, 새 중복 근거 보존과 링크 롤백·재생을 검증했다.
- 프론트 `npm.cmd run lint`, `npm.cmd run typecheck`, `npm.cmd test` 73건 및 `npm.cmd run build` 통과. OpenAPI lint는 기존 경고 2건과 함께 통과했고 생성 타입 일치 검증도 통과했다. gitleaks 8.30.1 변경분 검사에서 유출을 발견하지 않았다.
- 독립 리뷰, PR HEAD CI, 제품 실행기·UI 결합과 브라우저 인수 검증은 다음 단계다. 테스트에는 FAKE만 사용했다.

## Claude 결합 지점

1. 수동 선택 UI는 `GET …/discovery`에서 받은 `candidateId`에 해당하는 묶음의 `version`과 `batchToken`을 함께 `POST …/selection`으로 보낸다. 재전달에는 최초 전송의 값을 사용한다. 새 묶음이 조회되면 최신 값을 사용한다.
2. DISCOVER는 트랜잭션 밖에서 `discover`를 호출한다. 프롬프트 버전은 `ProductionDiscovery.PROMPT_VERSION`과 맞춘다. 결함 유형을 이미 확인한 FAKE 디코딩 오류는 `DiscoveryProvider.InvalidResponse`로 전달한다. 제품 측 `DiscoveryResponseException`은 이 타입을 상속하거나 공급자 경계에서 변환할 수 있다. 영수증 결과가 `RESULT_ADOPTED`일 때 `response`를 읽으며, 다른 최종 영수증은 본문 없이 기존 비용 적용 경로에서 처리한다. 성공 응답의 평가·영수증 적용·단계 전이는 같은 fenced 트랜잭션에서 처리한다.
3. SELECT는 시도 차감 전에 `lockForSelection` 후 `catalog`와 재평가를 실행한다. 같은 트랜잭션에서 `ProductionSelectionPort.link`, 재검사 목록을 받는 5인자 `recordSelection`, Run 시작과 단계 완료를 커밋한다. 수동 후보가 탈락하면 `invalidateManualChoice`로 의도를 해제하고 재검사 결과를 저장한다. 자동 후보가 모두 탈락하면 `recordExhaustedSelection`으로 근거를 저장하고 단계를 실패 처리한다. 선택 지문에는 요청·후보·본문 해시·선정 근거 버전·제목·brief·길이를 포함한다.
4. 제품 브랜치와 결합한 뒤 실제 FAKE 실행기로 R01~R05, 수동 대기·재시작, 자동 차순위, 표현이 다른 실질 중복의 동시 SELECT를 검증한다. 안전 브랜치의 테스트는 같은 제목의 두 요청이 최신 카탈로그를 보고 하나만 연결하는 경합을 실제 두 포트에서 검증한다.

공통 상태표 반영은 결합 담당자가 진행한다. 최종 통합 병합은 별도 승인 범위다.
