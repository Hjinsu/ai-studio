import { FormField, FormSection, Input, Select, Textarea } from '../../ui/Form'
import { minutes, type SettingsDraft } from './settings'

/**
 * 채널 설정 입력(관련 설정별 그룹 + 의미 설명). 채널 생성과 새 설정 revision에서 같이 쓴다(ADR-034, 039).
 */
export function ChannelSettingsFields({
  draft,
  onChange,
  errors,
}: {
  draft: SettingsDraft
  onChange: (draft: SettingsDraft) => void
  errors: Record<string, string>
}) {
  const set = <K extends keyof SettingsDraft>(key: K, value: SettingsDraft[K]) => onChange({ ...draft, [key]: value })
  const seconds = (value: string) => (/^\d+$/.test(value) ? minutes(Number(value)) : '')
  return (
    <div className="grid gap-6">
      <FormSection title="콘텐츠 방향" description="제작 단계에서 주제 선정과 대본 작성의 기준으로 쓰는 설명입니다.">
        <FormField label="콘텐츠 전략" optional hint="채널이 다루는 주제와 접근 방식. 예: 일상 속 물리 현상을 실험으로 설명" error={errors.contentStrategy}>
          <Textarea maxLength={2000} value={draft.contentStrategy} onChange={(e) => set('contentStrategy', e.target.value)} />
        </FormField>
        <FormField label="타깃 시청자" optional hint="누구를 위한 영상인지. 예: 과학에 관심 있는 중고등학생" error={errors.targetAudience}>
          <Input maxLength={500} value={draft.targetAudience} onChange={(e) => set('targetAudience', e.target.value)} />
        </FormField>
      </FormSection>
      <FormSection title="제작 스타일" description="음성과 화면 연출의 기본 톤입니다.">
        <FormField label="내레이션 스타일" optional hint="말투와 속도. 예: 차분하고 또박또박, 질문으로 시작" error={errors.narrationStyle}>
          <Input maxLength={500} value={draft.narrationStyle} onChange={(e) => set('narrationStyle', e.target.value)} />
        </FormField>
        <FormField label="시각 스타일" optional hint="화면 구성과 그림체. 예: 단순한 도식과 실험 사진 위주" error={errors.visualStyle}>
          <Input maxLength={500} value={draft.visualStyle} onChange={(e) => set('visualStyle', e.target.value)} />
        </FormField>
      </FormSection>
      <FormSection title="영상 길이" description="제작할 영상의 기본 유형과 유형별 목표 길이 범위입니다. 기본 유형은 제작 시 처음 선택되는 값일 뿐입니다.">
        <FormField label="기본 콘텐츠 유형">
          <Select value={draft.defaultContentType} onChange={(e) => set('defaultContentType', e.target.value as SettingsDraft['defaultContentType'])}>
            <option value="SHORTS">쇼츠</option>
            <option value="LONGFORM">롱폼</option>
          </Select>
        </FormField>
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="쇼츠 최소 길이(초)" hint="1~180초" error={errors.shortsMinSeconds}>
            <Input inputMode="numeric" value={draft.shortsMin} onChange={(e) => set('shortsMin', e.target.value)} required />
          </FormField>
          <FormField label="쇼츠 최대 길이(초)" hint="1~180초" error={errors.shortsMaxSeconds}>
            <Input inputMode="numeric" value={draft.shortsMax} onChange={(e) => set('shortsMax', e.target.value)} required />
          </FormField>
          <FormField label="롱폼 최소 길이(초)" hint={seconds(draft.longMin) || '60~14,400초'} error={errors.longformMinSeconds}>
            <Input inputMode="numeric" value={draft.longMin} onChange={(e) => set('longMin', e.target.value)} required />
          </FormField>
          <FormField label="롱폼 최대 길이(초)" hint={seconds(draft.longMax) || '60~14,400초'} error={errors.longformMaxSeconds}>
            <Input inputMode="numeric" value={draft.longMax} onChange={(e) => set('longMax', e.target.value)} required />
          </FormField>
        </div>
      </FormSection>
      <FormSection title="공개 설정">
        <label className="flex items-start gap-2.5 text-base">
          <input
            type="checkbox"
            className="mt-1 size-4 accent-[var(--c-primary)]"
            checked={draft.syntheticDisclosureDefault}
            onChange={(e) => set('syntheticDisclosureDefault', e.target.checked)}
          />
          <span>
            합성 콘텐츠 고지를 기본으로 선택
            <span className="block text-sm text-muted">
              AI로 만든 음성·영상임을 알리는 표시의 초기값입니다. 영상마다 실제 고지 여부를 다시 판단하며 이 값이 그 판단을 대신하지 않습니다.
            </span>
          </span>
        </label>
      </FormSection>
      <FormSection title="고급" description="provider(이미지·음성 생성 서비스) 옵션입니다. API 키 같은 비밀값은 넣지 마세요.">
        <FormField label="provider 옵션(JSON)" optional hint='예: {"imageModel": "draft"}' error={errors.providerOptions}>
          <Textarea className="font-mono text-sm" value={draft.providerOptions} onChange={(e) => set('providerOptions', e.target.value)} />
        </FormField>
      </FormSection>
    </div>
  )
}
