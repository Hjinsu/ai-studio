import { useMutation } from '@tanstack/react-query'
import { assertSession, currentSession } from './http'

export function useSessionMutation<TData, TVariables = void>({
  mutationFn,
  onSuccess,
  onError,
}: {
  mutationFn: (variables: TVariables) => Promise<TData>
  onSuccess?: (data: TData, variables: TVariables, generation: AbortSignal) => void | Promise<void>
  onError?: (error: Error, variables: TVariables, generation: AbortSignal) => void | Promise<void>
}) {
  const mutation = useMutation<TData, Error, { variables: TVariables; generation: AbortSignal }>({
    mutationFn: ({ variables, generation }) => {
      assertSession(generation)
      return mutationFn(variables)
    },
    onSuccess: (data, { variables, generation }) => {
      if (generation === currentSession()) return onSuccess?.(data, variables, generation)
    },
    onError: (error, { variables, generation }) => {
      if (generation === currentSession()) return onError?.(error, variables, generation)
    },
  })
  return { ...mutation, mutate: (variables: TVariables) => mutation.mutate({ variables, generation: currentSession() }) }
}
