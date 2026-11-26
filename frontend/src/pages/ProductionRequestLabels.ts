import { label, type Label, type Tone } from '../lib/labels'
import type { DiscoveryScores } from '../api/production'

/** 제작 요청·소재 발굴 화면의 한국어 표시. API 값은 바꾸지 않으며 모르는 값은 원문을 보여준다. */
const of = (text: string, tone: Tone): Label => ({ text, tone })

export const PRODUCTION_STATUS: Record<string, Label> = {
  QUEUED: of('대기', 'neutral'),
  RUNNING: of('진행 중', 'primary'),
  RECOVERY_REQUIRED: of('복구 확인 필요', 'warning'),
  SUCCEEDED: of('완료', 'success'),
  FAILED: of('실패', 'danger'),
  CANCELLED: of('취소됨', 'neutral'),
}

export const STEP_STATUS: Record<string, Label> = {
  PENDING: of('대기', 'neutral'),
  RUNNING: of('실행 중', 'primary'),
  RETRY_WAIT: of('재시도 대기', 'warning'),
  RECOVERY_REQUIRED: of('복구 확인 필요', 'warning'),
  SUCCEEDED: of('완료', 'success'),
  FAILED: of('실패', 'danger'),
  CANCELLED: of('취소됨', 'neutral'),
}

export const STAGE: Record<string, string> = {
  DISCOVER: '소재 발굴',
  SELECT: '소재 선정',
  RESEARCH: '자료 조사',
  SCRIPT: '대본',
  SCENES: '장면 구성',
  IMAGE: '이미지',
  TTS: '음성',
  SUBTITLE: '자막',
  RENDER: '렌더링',
  METADATA: '메타데이터',
  QC: '품질 검수',
}

export const SELECTION_MODE: Record<string, { text: string; help: string }> = {
  AUTO: { text: '자동 선정', help: '평가 점수가 가장 높은 적격 후보를 바로 선정하고 다음 단계로 진행합니다.' },
  MANUAL: { text: '직접 선택', help: '후보 평가가 끝나면 대기합니다. 이 화면에서 후보를 고르면 이어서 진행합니다.' },
}

export const BLOCK: Record<string, Label & { help: string }> = {
  DUPLICATE: { ...of('중복', 'danger'), help: '기존 소재·콘텐츠 또는 같은 묶음의 후보와 질문·설명 관점이 사실상 같습니다.' },
  SIMILARITY_UNCERTAIN: { ...of('검수 필요', 'warning'), help: '중복 여부가 불확실합니다. 직접 선택하려면 비교 근거를 확인해야 합니다.' },
  CHANNEL_PROHIBITED: { ...of('금지 소재', 'danger'), help: '채널 설정의 금지 주제 또는 기본 금지 주제에 해당합니다.' },
  UNSUPPORTED_ASSERTION: { ...of('근거 없는 주장', 'danger'), help: '승인된 근거 없이 사실을 단정하거나 확인되지 않은 출처를 제시했습니다.' },
}

/** 평가 항목(총점 100점). 순서는 화면 표시 순서. */
export const CRITERIA: { key: keyof DiscoveryScores; text: string; max: number }[] = [
  { key: 'interest', text: '흥미도', max: 25 },
  { key: 'channelFit', text: '채널 적합성', max: 20 },
  { key: 'originality', text: '독창성', max: 20 },
  { key: 'visualPotential', text: '시각화 가능성', max: 15 },
  { key: 'evidencePotential', text: '근거 확보 가능성', max: 15 },
  { key: 'costEfficiency', text: '비용 효율성', max: 5 },
]

export const SOURCE_TYPE: Record<string, string> = {
  PEER_REVIEWED_PAPER: '동료 심사 논문',
  SPACE_AGENCY: '우주 기관',
  GOVERNMENT_AGENCY: '정부 기관',
  UNIVERSITY_EXPLAINER: '대학 해설 자료',
  TEXTBOOK: '교과서',
  MUSEUM: '박물관',
  DATASET: '공개 데이터',
  ENCYCLOPEDIA: '백과사전',
}


/** 단계 마지막 오류 코드(`lastErrorCode`). 모르는 코드는 원문을 보여준다. */
export const STEP_ERROR: Record<string, string> = {
  DISCOVERY_NO_ELIGIBLE_CANDIDATE: '선정 가능한 후보 없음',
  DISCOVERY_RESPONSE_INVALID: '저장된 발굴 응답 형식 오류',
  DISCOVERY_RESPONSE_UNAVAILABLE: '발굴 응답 확인 대기',
  DISCOVERY_EVALUATION_FAILED: '후보 평가 재시도 대기',
  EFFECT_EXECUTED_FAILED: '외부 실행 실패(비용 확정) — 응답 형식 오류 포함',
  EFFECT_REJECTED: '비용 보호 규칙으로 실행 거부',
  EFFECT_NOT_EXECUTED: '외부 작업 미실행',
  EFFECT_UNKNOWN: '외부 실행 결과 불명(예약 유지)',
  EFFECT_WAITING: '외부 실행 결과 대기',
  EFFECT_RECOVERY_REQUIRED: '외부 실행 결과 확인 필요',
  EFFECT_PRICE_UNAVAILABLE: '비용 산정 정보 확인 필요',
  RECEIPT_INVALID: '실행 영수증 확인 필요',
  RECEIPT_APPLY_FAILED: '실행 결과 반영 재시도 대기',
  LOCAL_ERROR: '내부 처리 오류(재시도)',
  INTERRUPTED: '중단 후 시도 한도 도달',
  ATTEMPTS_EXHAUSTED: '시도 한도 도달',
  PLANNING_VALIDATION_BLOCKED: '검수 필수 차단(이후 단계 미실행)',
  PLANNING_RESPONSE_UNAVAILABLE: '기획 응답 확인 대기',
  PLANNING_REVIEW_FAILED: '기획 검수 재시도 대기',
}

/** 조사 주장 유형(V2-03). 사실만 출처 근거로 확인하며 가설·가상 시나리오는 불확실성을 함께 표시한다. */
export const CLAIM_KIND: Record<string, Label & { help: string }> = {
  FACT: { ...of('사실', 'success'), help: '보유한 문서의 인용으로 지지를 확인한 주장' },
  HYPOTHESIS: { ...of('가설', 'warning'), help: '추론 근거와 불확실성이 있는 설명' },
  SCENARIO: { ...of('가상 시나리오', 'primary'), help: '전제와 적용 한계를 둔 사고 실험' },
  UNVERIFIED: { ...of('미확인', 'neutral'), help: '근거가 확인되지 않아 대본에 쓰지 않는 내용' },
}

/** 출처 조회 상태. FIXTURE는 오프라인 시험 자료이며 실제 확인(VERIFIED)으로 표시하지 않는다. */
export const SOURCE_STATUS: Record<string, Label> = {
  VERIFIED: of('조회 확인', 'success'),
  FIXTURE: of('FIXTURE 시험 자료', 'warning'),
  UNVERIFIED: of('미확인 단서', 'neutral'),
}

export const SOURCE_ORIGIN: Record<string, string> = {
  RETRIEVED: '서버 조회 문서',
  MODEL_HINT: '모델 제시 단서',
  FAKE: 'FAKE 생성',
  FIXTURE: '오프라인 fixture',
}

/** 주장 지지 판정(출처 조회 상태와 별개). */
export const RELATION: Record<string, Label> = {
  SUPPORTS: of('지지', 'success'),
  CONTRADICTS: of('반박', 'danger'),
  INSUFFICIENT: of('근거 부족', 'neutral'),
}

export const SEVERITY: Record<string, Label> = {
  BLOCK: of('필수 차단', 'danger'),
  RECOMMENDATION: of('개선 권고', 'warning'),
}

/** 검수 지적 코드. 모르는 코드는 원문을 보여준다. */
export const FINDING: Record<string, string> = {
  RESEARCH_STRUCTURE: '조사 결과 구조 오류',
  RESEARCH_INPUT_MISMATCH: '선정 소재의 질문·관점과 다름',
  SOURCE_URL_INVALID: '허용되지 않는 출처 주소',
  SOURCE_FALSE_VERIFICATION: '서버가 확인하지 않은 검증 표시',
  SOURCE_NOT_FOUND: '보유하지 않은 출처(허위 출처 의심)',
  QUOTE_NOT_FOUND: '문서에 없는 인용(허위 인용 의심)',
  EVIDENCE_SOURCE_UNKNOWN: '목록에 없는 출처 연결',
  FACT_CONTRADICTED: '핵심 사실이 출처와 상충',
  CORE_EVIDENCE_INSUFFICIENT: '핵심 근거 부족',
  CORE_UNVERIFIED_CLAIM: '미확인 내용을 대본에 사용',
  CLAIM_UNCERTAINTY_MISSING: '가설·시나리오의 불확실성 누락',
  OPTIONAL_CLAIM_EXCLUDED: '확인되지 않은 부가 내용 제외',
  INVESTIGATION_PLAN_NOT_COVERED: '조사 계획 일부 미기록',
  LIMITATIONS_MISSING: '조사 한계 미기록',
  SCRIPT_STRUCTURE: '대본 구조 오류',
  SCRIPT_LANGUAGE_MISMATCH: '채널 언어와 다름',
  UNKNOWN_CLAIM_REFERENCE: '없는 주장 참조',
  EXCLUDED_CLAIM_REFERENCE: '대본 사용 대상이 아닌 주장 참조',
  UNSUPPORTED_ASSERTION: '근거 주장 없이 사실 단정',
  HYPOTHESIS_AS_FACT: '가설·가상을 사실로 단정',
  NUMBER_MISMATCH: '근거와 다른 수치',
  UNSUPPORTED_CAUSAL_CLAIM: '근거에 없는 인과 설명',
  TIMING_RULE_MISMATCH: '발화 시간 규칙 버전 불일치',
  DURATION_ESTIMATE_MISMATCH: '예상 길이 불일치',
  DURATION_UNSUITABLE: '길이 부적합(35~65초 밖)',
  DURATION_ADJUSTMENT: '길이 보정 권고(40~60초 밖)',
  HOOK_TOO_LONG: '첫 3초 Hook이 김',
  HOOK_WEAK: 'Hook 흡인력 부족',
  ENDING_WEAK: '결말 약함',
  EDITORIAL_INTENT_MISSING: '차별적 설명 의도 미기록',
  CLAIM_UNUSED: '조사한 핵심 주장 미사용',
  SUBSTANTIVE_REPEAT: '최근 대본과 실질적 반복',
  SIMILAR_RECENT_SCRIPT: '최근 대본과 유사',
  SCENE_STRUCTURE: '장면 구성 구조 오류',
  SCENE_ORDER: '장면 순서 번호 오류',
  SCENE_SEGMENT_MISSING: '대본 구간 누락',
  SCENE_SEGMENT_DUPLICATE: '대본 구간 중복',
  SCENE_SEGMENT_UNKNOWN: '없는 대본 구간 참조',
  SCENE_SEGMENT_ORDER: '대본 순서와 다른 장면 순서',
  SCENE_DURATION_INVALID: '장면 길이 오류',
  SCENE_DURATION_TOTAL: '장면 길이 합계 불일치',
  SCENE_TOO_SHORT: '너무 짧은 장면',
  SCENE_TEXT_MISMATCH: '내레이션·자막이 고정 대본과 다름',
  SCENE_VISUAL_MISSING: '시각 연출 정보 누락',
  SCENE_TRANSITION_MISSING: '장면 전환 미지정',
  SCENE_NEW_FACT: '화면에 대본에 없는 사실 추가',
  SCENE_PRESENTED_AS_EVIDENCE: '생성 이미지를 실제 자료처럼 표현',
  CHANNEL_STYLE_NOT_APPLIED: '채널 시각 스타일 미반영',
  FRAMING_NOT_VERTICAL: '9:16 세로 구도 미지정',
}

/**
 * V2-01~02 제작은 FAKE 공급자로만 실행한다. 완료 상태는 고정 단계와 비용 정산이 끝났다는 뜻이며 실제 영상 파일은
 * 만들지 않았다. 실제 공급자 연동 단계에서 API가 실행 방식을 제공하면 이 안내를 그 값으로 바꾼다.
 */
export const FAKE_RUN_NOTICE = 'FAKE 실행: 실제 AI 호출과 영상 파일 생성 없이 단계와 비용 정산만 검증합니다.'
export const FAKE_SUCCEEDED = of('FAKE 완료', 'success')

/** 완료는 FAKE 실행 완료로 표시해 실제 영상 완성으로 오인하지 않게 한다. */
export function productionStatusLabel(status: string): Label {
  return status === 'SUCCEEDED' ? FAKE_SUCCEEDED : label(PRODUCTION_STATUS, status)
}
