/**
 * 세션 만료 안내 플래그. 로그인된 상태에서 401을 받아 로그인 화면으로 돌아갈 때 설정하고, 로그인 화면이 한 번 표시한다.
 * 로그아웃(사용자 동작)에서는 설정하지 않는다.
 */
let expired = false

export function markSessionExpired(): void {
  expired = true
}

export function consumeSessionExpired(): boolean {
  const value = expired
  expired = false
  return value
}
