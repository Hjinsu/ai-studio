import { useMutation, type QueryClient, type QueryKey } from '@tanstack/react-query'
import { assertSession, currentSession, SessionChangedError } from './http'

/** Bind in-flight and paused callback queries to the exact fetches started in this session. */
export async function runSessionQuery<T>(
  client: QueryClient, generation: AbortSignal, keys: QueryKey[], run: () => Promise<T>,
): Promise<T> {
  assertSession(generation)
  const result = run()
  const queries = new Set(keys.flatMap((queryKey) => client.getQueryCache().findAll({ queryKey })))
  const fetches = [...queries].flatMap((query) => {
    const promise = query.promise
    return promise && query.state.fetchStatus !== 'idle' ? [{ query, promise }] : []
  })
  const cancel = () => {
    for (const { query, promise } of fetches) {
      if (query.promise === promise) void query.cancel({ revert: true })
    }
  }
  generation.addEventListener('abort', cancel, { once: true })
  if (generation.aborted) cancel()
  void Promise.allSettled(fetches.map(({ promise }) => promise))
    .then(() => generation.removeEventListener('abort', cancel))
  try {
    return await result
  } finally {
    assertSession(generation)
  }
}

export function invalidateSessionQueries(client: QueryClient, generation: AbortSignal, ...keys: QueryKey[]) {
  return runSessionQuery(client, generation, keys, () =>
    Promise.all(keys.map((queryKey) => {
      assertSession(generation)
      return client.invalidateQueries({ queryKey })
    })),
  )
}

async function runCallback(generation: AbortSignal, run: () => void | Promise<void>) {
  if (generation !== currentSession()) return
  try {
    await run()
  } catch (error) {
    if (!(error instanceof SessionChangedError)) throw error
  }
}

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
      return runCallback(generation, () => onSuccess?.(data, variables, generation))
    },
    onError: (error, { variables, generation }) => {
      return runCallback(generation, () => onError?.(error, variables, generation))
    },
  })
  return { ...mutation, mutate: (variables: TVariables) => mutation.mutate({ variables, generation: currentSession() }) }
}
