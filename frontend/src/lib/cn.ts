import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** 조건부 클래스 결합 + Tailwind 충돌 정리(뒤에 오는 클래스 우선). */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}
