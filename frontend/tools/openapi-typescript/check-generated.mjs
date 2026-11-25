// 생성 타입이 현재 OpenAPI 명세와 일치하는지 검사한다(ADR-033: 생성물 커밋, 직접 수정 금지, CI diff 검사).
// 임시 파일로 재생성해 커밋된 파일과 바이트 단위로 비교한다. 상대 경로만 사용한다.
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const committed = '../../src/api/generated/schema.d.ts'
const dir = mkdtempSync(join(tmpdir(), 'ai-studio-openapi-'))
const fresh = join(dir, 'schema.d.ts')
try {
  execFileSync(process.execPath, ['node_modules/openapi-typescript/bin/cli.js', '../../../docs/api/openapi.yaml', '-o', fresh],
    { stdio: 'inherit' })
  const normalize = (text) => text.replace(/\r\n/g, '\n')
  if (normalize(readFileSync(fresh, 'utf8')) !== normalize(readFileSync(committed, 'utf8'))) {
    console.error('src/api/generated/schema.d.ts가 docs/api/openapi.yaml과 다릅니다. `npm run generate` 후 커밋하세요.')
    process.exit(1)
  }
  console.log('Generated types are up to date.')
} finally {
  rmSync(dir, { recursive: true, force: true })
}
