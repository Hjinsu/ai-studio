/** 화면 표시용 한국어 레이블. API 값(영문 enum)은 그대로 두고 표시만 바꾼다. */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger'

export interface Label {
  text: string
  tone: Tone
}

const of = (text: string, tone: Tone): Label => ({ text, tone })

export const CHANNEL_STATUS: Record<string, Label> = {
  ACTIVE: of('운영 중', 'success'),
  PAUSED: of('일시정지', 'warning'),
  ARCHIVED: of('보관됨', 'neutral'),
}

export const POLICY_STATUS: Record<string, Label> = {
  ACTIVE: of('활성', 'success'),
  DISABLED: of('비활성', 'neutral'),
}

export const SCOPE: Record<string, string> = {
  SYSTEM: '시스템 전체',
  CHANNEL: '채널별',
  CONTENT_TYPE: '콘텐츠 유형',
}

export const PERIOD: Record<string, string> = {
  DAY: '일간',
  MONTH: '월간',
  LIFETIME: '콘텐츠당',
}

export const CONTENT_TYPE: Record<string, string> = {
  SHORTS: '쇼츠',
  LONGFORM: '롱폼',
}

export const RESERVATION_STATUS: Record<string, Label> = {
  ACTIVE: of('예약 진행 중', 'primary'),
  RECONCILIATION_REQUIRED: of('정산 확인 필요', 'danger'),
  SETTLED: of('정산 완료', 'success'),
  RELEASED: of('예약 해제', 'neutral'),
}

export const CONSISTENCY: Record<string, Label> = {
  UNKNOWN: of('미검사', 'neutral'),
  OK: of('정상', 'success'),
  ERROR: of('오류', 'danger'),
}

/** 원장 이벤트 유형과 의미(예약·비용 관계 설명). */
export const ENTRY_TYPE: Record<string, Label & { help: string }> = {
  RESERVE: { ...of('예약', 'primary'), help: '예상 비용만큼 예산을 미리 잡아 둡니다.' },
  PROVISIONAL: { ...of('잠정 비용', 'warning'), help: '확정 전 비용입니다. 남은 예약과 비용 사이에서 금액이 옮겨집니다.' },
  CONFIRM: { ...of('확정 비용', 'success'), help: '최종 비용을 기록하고 남은 예약을 비웁니다.' },
  RELEASE: { ...of('예약 해제', 'neutral'), help: '과금되지 않아 남은 예약을 돌려놓습니다.' },
  ADJUSTMENT: { ...of('조정', 'warning'), help: '정산 후 비용을 늘리거나 줄입니다.' },
  REFUND: { ...of('환불', 'success'), help: '정산 후 환불받은 금액을 비용에서 뺍니다.' },
}

export const ACTOR: Record<string, string> = {
  ADMIN: '관리자',
  CLI: '관리 명령',
  SYSTEM: '시스템',
}

export const REASON: Record<string, string> = {
  OUTCOME_CONFIRMED: '과금 확인',
  NOT_CHARGED: '미과금 확인',
  LATE_CHARGE: '지연 과금',
  FX_DIFFERENCE: '환율 차이',
  PROVIDER_INVOICE: '공급사 청구서',
  MANUAL_CORRECTION: '수동 정정',
}

export const CHECK_CODE: Record<string, string> = {
  RESERVATION_RESERVED_MISMATCH: '예약의 남은 예약액이 원장 합계와 다릅니다.',
  RESERVATION_COST_MISMATCH: '예약의 인식 비용이 원장 합계와 다릅니다.',
  RESERVATION_ORIGINAL_COST_MISMATCH: '예약의 원본 통화 비용이 원장 합계와 다릅니다.',
  BUCKET_RESERVED_MISMATCH: '기간 예산의 예약액이 연결된 원장 합계와 다릅니다.',
  BUCKET_SPENT_MISMATCH: '기간 예산의 지출액이 연결된 원장 합계와 다릅니다.',
  BUCKET_OVERLAP: '같은 정책의 기간이 겹칩니다.',
  BUCKET_REVISION_MISMATCH: '기간 예산의 한도가 정책 revision과 다릅니다.',
  CLOSED_RESERVATION_HAS_RESERVE: '종료된 예약에 남은 예약액이 있습니다.',
  MISSING_RESERVE_EVENT: '예약 이벤트가 없는 예약이 있습니다.',
}

export function label(map: Record<string, Label>, value: string): Label {
  return map[value] ?? of(value, 'neutral')
}

export function text(map: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return '—'
  }
  return map[value] ?? value
}
