import { LinkButton } from '../ui/Button'
import { EmptyState } from '../ui/States'

export function NotFoundPage() {
  return (
    <EmptyState
      title="페이지를 찾을 수 없습니다"
      description="주소가 바뀌었거나 없는 화면입니다."
      action={<LinkButton to="/" variant="primary">대시보드로 이동</LinkButton>}
    />
  )
}
