import type { Label, Tone } from '../lib/labels'
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
  SIMILARITY_UNCERTAIN: { ...of('유사성 판단 보류', 'warning'), help: '기존 소재와 비슷해 차별성을 확인할 수 없어 선택할 수 없습니다.' },
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

