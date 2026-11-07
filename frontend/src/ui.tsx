import { useEffect, useRef, type ReactNode } from 'react'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'

export function Pill({ children, selected, onClick }: { children: ReactNode; selected?: boolean; onClick: () => void }) {
  return <button className={`pill ${selected ? 'selected' : ''}`} type="button" aria-pressed={selected ?? false} onClick={onClick}>{children}</button>
}
export function Status({ error, loading, retry }: { error?: string; loading?: boolean; retry?: () => void }) {
  if (error) return <div className="status error" role="alert">{error}{retry && <button type="button" className="text-button" onClick={retry}>다시 시도</button>}</div>
  return loading ? <div className="status" role="status">불러오는 중…</div> : null
}
export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>
}
export function Pagination({ page, pages, onPage }: { page: number; pages: number; onPage: (n: number) => void }) {
  if (pages <= 1) return null
  const start = Math.max(0, Math.min(page - 2, pages - 5))
  return <nav aria-label="페이지 이동" className="pagination">
    <button type="button" aria-label="이전 페이지" disabled={page === 0} onClick={() => onPage(page - 1)}><ChevronLeft size={15} /></button>
    {Array.from({ length: Math.min(pages, 5) }, (_, i) => i + start).map(i => <button key={i} type="button"
      aria-current={i === page ? 'page' : undefined} onClick={() => onPage(i)}>{i + 1}</button>)}
    <button type="button" aria-label="다음 페이지" disabled={page === pages - 1} onClick={() => onPage(page + 1)}><ChevronRight size={15} /></button>
  </nav>
}

export function Drawer({ title, children, onClose, className = '' }: { title: string; children: ReactNode; onClose: () => void; className?: string }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const el = dialog.current
    el?.showModal()
    return () => el?.close()
  }, [])
  return <dialog ref={dialog} className={`drawer ${className}`} aria-label={title}
    onCancel={event => { event.preventDefault(); onClose() }}
    onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    <div className="drawer-body">
      <button className="icon-button drawer-close" onClick={onClose} type="button" aria-label="닫기"><X size={20} /></button>
      {children}
    </div>
  </dialog>
}
