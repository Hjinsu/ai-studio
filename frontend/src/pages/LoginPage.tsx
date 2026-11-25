import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { login } from '../api/admin'
import { ME_KEY } from '../queryClient'
import { ErrorAlert, Field } from '../ui/components'

/** 관리자 로그인(ADR-037). 실패 사유(계정 없음·비밀번호 오류)는 구분하지 않고 서버 응답을 그대로 표시한다. */
export function LoginPage() {
  const queryClient = useQueryClient()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const signIn = useMutation({
    mutationFn: () => login({ login: loginId, password }),
    onSuccess: (user) => {
      setPassword('')
      queryClient.setQueryData(ME_KEY, user)
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    signIn.mutate()
  }
  return (
    <main className="centered">
      <form onSubmit={submit} className="card login" aria-labelledby="login-heading">
        <h1 id="login-heading">AI Studio 관리자 로그인</h1>
        <Field label="아이디">
          <input value={loginId} onChange={(e) => setLoginId(e.target.value)} autoComplete="username" required
            maxLength={64} />
        </Field>
        <Field label="비밀번호">
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password" required maxLength={256} />
        </Field>
        <button type="submit" className="primary" disabled={signIn.isPending}>
          {signIn.isPending ? '로그인 중…' : '로그인'}
        </button>
        <ErrorAlert error={signIn.error} />
      </form>
    </main>
  )
}
