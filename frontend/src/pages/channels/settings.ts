import type { ChannelConfig, ChannelConfigSettings } from '../../api/admin'
import { CONTENT_TYPE, text } from '../../lib/labels'

/** 채널 설정 필드의 화면 이름과 표시 형식(비교·이력 공용). */
export const SETTING_FIELDS: { key: keyof ChannelConfigSettings; label: string; format: (s: ChannelConfigSettings) => string }[] = [
  { key: 'defaultContentType', label: '기본 콘텐츠 유형', format: (s) => text(CONTENT_TYPE, s.defaultContentType) },
  { key: 'shortsMinSeconds', label: '쇼츠 길이', format: (s) => `${s.shortsMinSeconds}–${s.shortsMaxSeconds}초` },
  { key: 'longformMinSeconds', label: '롱폼 길이', format: (s) => `${minutes(s.longformMinSeconds)}–${minutes(s.longformMaxSeconds)}` },
  { key: 'contentStrategy', label: '콘텐츠 전략', format: (s) => s.contentStrategy ?? '미입력' },
  { key: 'targetAudience', label: '타깃 시청자', format: (s) => s.targetAudience ?? '미입력' },
  { key: 'narrationStyle', label: '내레이션 스타일', format: (s) => s.narrationStyle ?? '미입력' },
  { key: 'visualStyle', label: '시각 스타일', format: (s) => s.visualStyle ?? '미입력' },
  { key: 'syntheticDisclosureDefault', label: '합성 콘텐츠 고지 기본값', format: (s) => (s.syntheticDisclosureDefault ? '고지함' : '고지 안 함') },
  { key: 'providerOptions', label: 'provider 옵션', format: (s) => (s.providerOptions ? JSON.stringify(s.providerOptions) : '없음') },
]

export function minutes(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return s === 0 ? `${m}분` : `${m}분 ${s}초`
}

export function settingsOf(config: ChannelConfig): ChannelConfigSettings {
  return {
    defaultContentType: config.defaultContentType,
    shortsMinSeconds: config.shortsMinSeconds,
    shortsMaxSeconds: config.shortsMaxSeconds,
    longformMinSeconds: config.longformMinSeconds,
    longformMaxSeconds: config.longformMaxSeconds,
    contentStrategy: config.contentStrategy ?? null,
    targetAudience: config.targetAudience ?? null,
    narrationStyle: config.narrationStyle ?? null,
    visualStyle: config.visualStyle ?? null,
    syntheticDisclosureDefault: config.syntheticDisclosureDefault,
    providerOptions: config.providerOptions ?? null,
  }
}

/** 두 설정의 표시값 차이(이력 비교). 길이는 최소·최대를 한 항목으로 본다. */
export function diffSettings(before: ChannelConfigSettings, after: ChannelConfigSettings) {
  return SETTING_FIELDS.map((field) => ({ field: field.label, before: field.format(before), after: field.format(after) })).filter(
    (change) => change.before !== change.after,
  )
}

/** 서버 기본값과 같은 초기 입력값(채널 생성 시 initialConfig를 보내지 않으면 서버 기본값 사용). */
export const DEFAULT_SETTINGS: ChannelConfigSettings = {
  defaultContentType: 'SHORTS',
  shortsMinSeconds: 40,
  shortsMaxSeconds: 60,
  longformMinSeconds: 480,
  longformMaxSeconds: 720,
  contentStrategy: null,
  targetAudience: null,
  narrationStyle: null,
  visualStyle: null,
  syntheticDisclosureDefault: true,
  providerOptions: null,
}

/** 폼 상태: 숫자·JSON은 입력 문자열 그대로 두고 제출 시 검증·변환한다. */
export interface SettingsDraft {
  defaultContentType: 'SHORTS' | 'LONGFORM'
  shortsMin: string
  shortsMax: string
  longMin: string
  longMax: string
  contentStrategy: string
  targetAudience: string
  narrationStyle: string
  visualStyle: string
  syntheticDisclosureDefault: boolean
  providerOptions: string
}

export function draftOf(settings: ChannelConfigSettings): SettingsDraft {
  return {
    defaultContentType: settings.defaultContentType,
    shortsMin: String(settings.shortsMinSeconds),
    shortsMax: String(settings.shortsMaxSeconds),
    longMin: String(settings.longformMinSeconds),
    longMax: String(settings.longformMaxSeconds),
    contentStrategy: settings.contentStrategy ?? '',
    targetAudience: settings.targetAudience ?? '',
    narrationStyle: settings.narrationStyle ?? '',
    visualStyle: settings.visualStyle ?? '',
    syntheticDisclosureDefault: settings.syntheticDisclosureDefault,
    providerOptions: settings.providerOptions ? JSON.stringify(settings.providerOptions, null, 2) : '',
  }
}

const blank = (value: string) => (value.trim() === '' ? null : value)

/** 입력 검증(서버 검증과 같은 범위). 통과하면 API 형식, 실패하면 필드별 오류. */
export function toSettings(draft: SettingsDraft): { settings?: ChannelConfigSettings; errors: Record<string, string> } {
  const errors: Record<string, string> = {}
  const int = (value: string, key: string, min: number, max: number) => {
    const n = Number(value)
    if (!/^\d+$/.test(value) || n < min || n > max) {
      errors[key] = `${min}~${max} 사이의 정수를 입력하세요.`
    }
    return n
  }
  const shortsMin = int(draft.shortsMin, 'shortsMinSeconds', 1, 180)
  const shortsMax = int(draft.shortsMax, 'shortsMaxSeconds', 1, 180)
  const longMin = int(draft.longMin, 'longformMinSeconds', 60, 14400)
  const longMax = int(draft.longMax, 'longformMaxSeconds', 60, 14400)
  if (!errors.shortsMinSeconds && !errors.shortsMaxSeconds && shortsMin > shortsMax) {
    errors.shortsMaxSeconds = '최대 길이는 최소 길이보다 짧을 수 없습니다.'
  }
  if (!errors.longformMinSeconds && !errors.longformMaxSeconds && longMin > longMax) {
    errors.longformMaxSeconds = '최대 길이는 최소 길이보다 짧을 수 없습니다.'
  }
  let providerOptions: Record<string, unknown> | null = null
  if (draft.providerOptions.trim() !== '') {
    try {
      const parsed: unknown = JSON.parse(draft.providerOptions)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) throw new Error('not object')
      providerOptions = parsed as Record<string, unknown>
    } catch {
      errors.providerOptions = 'JSON 객체 형식이어야 합니다. 예: {"imageModel": "draft"}'
    }
  }
  if (Object.keys(errors).length > 0) {
    return { errors }
  }
  return {
    errors,
    settings: {
      defaultContentType: draft.defaultContentType,
      shortsMinSeconds: shortsMin,
      shortsMaxSeconds: shortsMax,
      longformMinSeconds: longMin,
      longformMaxSeconds: longMax,
      contentStrategy: blank(draft.contentStrategy),
      targetAudience: blank(draft.targetAudience),
      narrationStyle: blank(draft.narrationStyle),
      visualStyle: blank(draft.visualStyle),
      syntheticDisclosureDefault: draft.syntheticDisclosureDefault,
      providerOptions,
    },
  }
}
