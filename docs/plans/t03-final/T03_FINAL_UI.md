# T03 관리자 UI

기존 디자인 시스템 컴포넌트와 기존 세션·CSRF 계약을 사용한다. Topic 목록/중복 탐지, Content 목록/Revision 이력, Run/Job 및 attempt/event, Queue 상태, dashboard production 지표를 추가한다. 실제 승인 및 게시 제어를 FAKE 화면에서 제공하지 않는다. Run 취소와 관리자 재개, queue 토글에 확인을 요청한다. 서버 선택 pipeline과 유효 Lease/미해결 비용을 분리하여 표시한다.

예약 상세는 직접 resolve/adjustments 버튼을 영속 의도 등록 → 내용을 확인 → 동일 ID 실행으로 전환한다. 새로고침·세션 변경 후 서버의 미완결 또는 최근 확정 의도를 조회한다. 503·네트워크 불명은 결과 불명으로 표시하고 자동 재생성하지 않는다. 별개 동일 금액 조정은 직전 receipt를 보여 주고 명시 확인 후 신규 키를 발급한다. 증거 및 청구 상태와 artifact 상태를 별도 표시한다.

로딩·빈 상태·오류·권한·세션 만료, 한국어 레이블, 키보드 및 모바일, 라이트/다크를 확인한다. 날짜는 DB UTC에서 사용자/채널 시간대로 표시하되 LIFETIME sentinel은 숨긴다. 금액은 decimal.ts로 문자열 처리하고 SYSTEM/CHANNEL/LIFETIME을 총비용으로 합산하지 않는다. 세션 generation 변경 시 이전 콜백을 차단한다.
