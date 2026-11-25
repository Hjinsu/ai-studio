import { useState, type FormEvent } from 'react'
import type { ChannelConfigSettings } from '../api/admin'
import { Field } from '../ui/components'

type ContentType = ChannelConfigSettings['defaultContentType']

function blankToNull(value: string): string | null {
  return value.trim() === '' ? null : value
}

/**
 * 채널 설정 입력(ADR-034, 039). provider 옵션은 JSON 객체(비밀값 입력 금지). 합성 고지 기본값은 UI 초기값일 뿐
 * 영상별 판단을 대체하지 않는다.
 */
export function ChannelSettingsForm({
  initial,
  disabled,
  submitLabel,
  onSubmit,
}: {
  initial: ChannelConfigSettings
  disabled: boolean
  submitLabel: string
  onSubmit: (settings: ChannelConfigSettings, changeNote: string) => void
}) {
  const [contentType, setContentType] = useState<ContentType>(initial.defaultContentType)
  const [shortsMin, setShortsMin] = useState(String(initial.shortsMinSeconds))
  const [shortsMax, setShortsMax] = useState(String(initial.shortsMaxSeconds))
  const [longMin, setLongMin] = useState(String(initial.longformMinSeconds))
  const [longMax, setLongMax] = useState(String(initial.longformMaxSeconds))
  const [strategy, setStrategy] = useState(initial.contentStrategy ?? '')
  const [audience, setAudience] = useState(initial.targetAudience ?? '')
  const [narration, setNarration] = useState(initial.narrationStyle ?? '')
  const [visual, setVisual] = useState(initial.visualStyle ?? '')
  const [disclosure, setDisclosure] = useState(initial.syntheticDisclosureDefault)
  const [options, setOptions] = useState(initial.providerOptions ? JSON.stringify(initial.providerOptions, null, 2) : '')
  const [changeNote, setChangeNote] = useState('')
  const [optionsError, setOptionsError] = useState<string | null>(null)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    let providerOptions: ChannelConfigSettings['providerOptions'] = null
    if (options.trim() !== '') {
      try {
        const parsed: unknown = JSON.parse(options)
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          throw new Error('not an object')
        }
        providerOptions = parsed as Record<string, unknown>
      } catch {
        setOptionsError('provider 옵션은 JSON 객체여야 합니다.')
        return
      }
    }
    setOptionsError(null)
    onSubmit(
      {
        defaultContentType: contentType,
        shortsMinSeconds: Number(shortsMin),
        shortsMaxSeconds: Number(shortsMax),
        longformMinSeconds: Number(longMin),
        longformMaxSeconds: Number(longMax),
        contentStrategy: blankToNull(strategy),
        targetAudience: blankToNull(audience),
        narrationStyle: blankToNull(narration),
        visualStyle: blankToNull(visual),
        syntheticDisclosureDefault: disclosure,
        providerOptions,
      },
      changeNote,
    )
  }

  return (
    <form onSubmit={submit} className="form-grid">
      <Field label="기본 콘텐츠 유형">
        <select value={contentType} onChange={(e) => setContentType(e.target.value as ContentType)}>
          <option value="SHORTS">SHORTS</option>
          <option value="LONGFORM">LONGFORM</option>
        </select>
      </Field>
      <Field label="Shorts 최소(초)">
        <input type="number" min={1} max={180} required value={shortsMin} onChange={(e) => setShortsMin(e.target.value)} />
      </Field>
      <Field label="Shorts 최대(초)">
        <input type="number" min={1} max={180} required value={shortsMax} onChange={(e) => setShortsMax(e.target.value)} />
      </Field>
      <Field label="Longform 최소(초)">
        <input type="number" min={60} max={14400} required value={longMin} onChange={(e) => setLongMin(e.target.value)} />
      </Field>
      <Field label="Longform 최대(초)">
        <input type="number" min={60} max={14400} required value={longMax} onChange={(e) => setLongMax(e.target.value)} />
      </Field>
      <Field label="콘텐츠 전략">
        <textarea maxLength={2000} value={strategy} onChange={(e) => setStrategy(e.target.value)} />
      </Field>
      <Field label="대상 시청자">
        <input maxLength={500} value={audience} onChange={(e) => setAudience(e.target.value)} />
      </Field>
      <Field label="내레이션 스타일">
        <input maxLength={500} value={narration} onChange={(e) => setNarration(e.target.value)} />
      </Field>
      <Field label="시각 스타일">
        <input maxLength={500} value={visual} onChange={(e) => setVisual(e.target.value)} />
      </Field>
      <label className="checkbox">
        <input type="checkbox" checked={disclosure} onChange={(e) => setDisclosure(e.target.checked)} />
        합성 콘텐츠 고지 기본 선택(영상별 판단을 대체하지 않음)
      </label>
      <Field label="provider 옵션(JSON, 비밀값 금지)" hint="예: {&quot;imageModel&quot;: &quot;draft&quot;}">
        <textarea value={options} onChange={(e) => setOptions(e.target.value)} aria-invalid={optionsError !== null} />
      </Field>
      {optionsError && <p role="alert" className="error">{optionsError}</p>}
      <Field label="변경 메모">
        <input maxLength={500} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} />
      </Field>
      <button type="submit" className="primary" disabled={disabled}>
        {submitLabel}
      </button>
    </form>
  )
}
