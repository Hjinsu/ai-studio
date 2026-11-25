import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter } from 'react-router'
import { App } from './App'
import { createQueryClient } from './queryClient'
import { routes } from './routes'
import './styles.css'

const queryClient = createQueryClient()
const router = createBrowserRouter(routes)

const rootElement = document.getElementById('root')
if (rootElement === null) {
  throw new Error('Root element #root not found')
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
