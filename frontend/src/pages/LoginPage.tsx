import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Eye, EyeOff, LogIn } from 'lucide-react'
import { login } from '../api/admin'
import { ME_KEY } from '../queryClient'
import { consumeSessionExpired } from '../lib/session'
import { Button, IconButton } from '../ui/Button'
import { FormField, Input } from '../ui/Form'
import { ErrorState, InlineNotice } from '../ui/States'

/**
 * 관리자 로그인(ADR-037). CSRF·rate limit·실패 사유 비구분 정책은 서버가 결정하며 화면은 응답을 그대로 안내한다.
 * 진행 중에는 버튼을 막아 중복 제출을 방지한다.
 */
export function LoginPage() {
  const queryClient = useQueryClient()
  const [sessionExpired] = useState(consumeSessionExpired)
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const signIn = useMutation({
    mutationFn: () => login({ login: loginId.trim(), password }),
    onSuccess: (user) => {
      setPassword('')
      queryClient.setQueryData(ME_KEY, user)
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!signIn.isPending) {
      signIn.mutate()
    }
  }
  return (
    <main className="grid min-h-screen place-items-center bg-bg px-4 py-10">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <span aria-hidden className="grid size-9 place-items-center rounded-md bg-primary text-base font-bold text-primary-fg">
            AI
          </span>
          <div>
            <p className="text-md font-semibold tracking-tight">AI Studio</p>
            <p className="text-sm text-muted">운영 관리 콘솔</p>
          </div>
        </div>
        <form onSubmit={submit} aria-labelledby="login-heading" className="grid gap-4 rounded-lg border border-line bg-surface p-6">
          <h1 id="login-heading" className="text-lg font-semibold">
            관리자 로그인
          </h1>
          {sessionExpired && (
            <InlineNotice tone="warning">세션이 만료되었거나 비밀번호가 변경되어 로그아웃되었습니다. 다시 로그인하세요.</InlineNotice>
          )}
          <FormField label="아이디">
            <Input value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" required maxLength={64} autoFocus />
          </FormField>
          <FormField label="비밀번호">
            <PasswordInput value={password} onChange={setPassword} reveal={reveal} onToggle={() => setReveal((v) => !v)} />
          </FormField>
          <Button type="submit" variant="primary" loading={signIn.isPending} icon={<LogIn aria-hidden className="size-4" />}>
            {signIn.isPending ? '로그인 중…' : '로그인'}
          </Button>
          {signIn.error && <ErrorState error={signIn.error} compact />}
        </form>
        <p className="mt-4 text-sm text-muted">
          관리자 계정은 서버의 관리 명령(admin create)으로만 만듭니다. 여러 번 실패하면 일정 시간 로그인이 제한됩니다.
        </p>
      </div>
    </main>
  )
}

function PasswordInput({
  value,
  onChange,
  reveal,
  onToggle,
  ...rest
}: {
  value: string
  onChange: (value: string) => void
  reveal: boolean
  onToggle: () => void
  id?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}) {
  return (
    <div className="relative">
      <Input
        {...rest}
        type={reveal ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        autoComplete="current-password"
        required
        maxLength={256}
        className="pr-10"
      />
      <IconButton
        label={reveal ? '비밀번호 숨기기' : '비밀번호 표시'}
        aria-pressed={reveal}
        size="sm"
        className="absolute top-0.5 right-0.5"
        onClick={onToggle}
        icon={reveal ? <EyeOff aria-hidden className="size-4" /> : <Eye aria-hidden className="size-4" />}
      />
    </div>
  )
}
