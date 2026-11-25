import { createContext, useContext } from 'react'

export interface ToastMessage {
  id: number
  tone: 'success' | 'error'
  text: string
}

export interface ToastApi {
  success: (text: string, generation?: AbortSignal) => void
  error: (text: string, generation?: AbortSignal) => void
}

export const ToastContext = createContext<ToastApi>({ success: () => {}, error: () => {} })

/** 저장·변경 결과 알림. 오류의 상세는 폼 안에 표시하고 토스트는 결과 요약만 알린다. */
export function useToast(): ToastApi {
  return useContext(ToastContext)
}
