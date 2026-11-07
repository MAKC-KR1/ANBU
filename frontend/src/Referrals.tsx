import { useState, type FormEvent } from 'react'
import { Bell, CalendarDays, ChevronRight, RefreshCw } from 'lucide-react'
import { api, dateLabel, seoulDate, useRemote, type Page, type ReferralDirection, type ReferralRequest,
  type Session, type TestType } from './api'
import { Empty, Pagination, Pill, Status } from './ui'

const labels = { PENDING: '요청 대기', PLANNED: '방문 예정', DONE: '완료' } as const
const statuses = ['PENDING', 'PLANNED', 'DONE'] as const
type Filter = 'all' | ReferralRequest['status']
type Props = {
  direction: ReferralDirection; session: Session; tests: TestType[]; revision: number
  onFulfill: (request: ReferralRequest) => void
  onAlertsChanged: () => void
}

export function Referrals({ direction, session, tests, revision, onFulfill, onAlertsChanged }: Props) {
  const [filter, setFilter] = useState<Filter>('all')
  const [page, setPage] = useState(0)
  const [selection, setSelection] = useState<number>()
  const [notice, setNotice] = useState('')
  const status = filter === 'all' ? statuses.join(',') : filter
  const received = direction === 'incoming'
  const requests = useRemote<Page<ReferralRequest>>(
    `/test-requests?direction=${direction}&assignable=false&status=${status}&page=${page}&size=8`,
    session.accessToken, revision,
  )
  const rows = requests.data?.content ?? []
  const selected = rows.find(row => row.requestId === selection) ?? rows[0]
  const canHandle = selected && received && tests.some(type => type.id === selected.testType.id
    && type.responsibleOrganizations.some(org => org.id === session.user.organization.id))

  function updateFilter(next: Filter) {
    setFilter(next); setPage(0); setSelection(undefined); setNotice('')
  }

  return <section className="directory-panel referral-panel" aria-label="연계 요청">
    <div className="spread referral-heading">
      <div><h1>연계 요청</h1><p className="muted">기관 계정으로 받은 요청과 보낸 요청의 진행 상태를 확인합니다.</p></div>
      <button type="button" className="secondary" onClick={() => { setNotice(''); requests.retry(); onAlertsChanged() }}>
        <RefreshCw size={15} /> 새로고침
      </button>
    </div>
    <div className="directory-toolbar">
      <nav className="referral-tabs" aria-label="연계 요청함">
        <a href="#/requests" aria-current={received ? 'page' : undefined}>받은 요청</a>
        <a href="#/requests/outgoing" aria-current={!received ? 'page' : undefined}>보낸 요청</a>
      </nav>
      <div className="filters" aria-label="요청 상태 필터">
        <Pill selected={filter === 'all'} onClick={() => updateFilter('all')}>전체</Pill>
        {statuses.map(value => <Pill key={value} selected={filter === value} onClick={() => updateFilter(value)}>{labels[value]}</Pill>)}
      </div>
    </div>
    {notice && <p className="success" role="status">{notice}</p>}
    <Status {...requests} />
    {requests.data && <div className="directory-layout">
      <section className="directory-list" aria-label={received ? '받은 요청 목록' : '보낸 요청 목록'}>
        <div className="spread list-title"><h2>{received ? '받은 요청' : '보낸 요청'} <small>{requests.data.totalElements}건</small></h2></div>
        {rows.length ? <ul className="directory-rows referral-rows">{rows.map(row => <li key={row.requestId}>
          <button type="button" aria-pressed={selected?.requestId === row.requestId} onClick={() => setSelection(row.requestId)}>
            <span><strong>{row.testType.name}</strong>{row.unread && <span className="referral-unread-label">새 알림</span>}
              <small>{row.place.name}</small>
              <small>{(received ? row.requestedByOrganization : row.recipientOrganization)?.name} · {dateLabel(row.requestedDate)}</small></span>
            <span className={`badge${row.status === 'PENDING' ? ' badge-orange' : ''}`}>{labels[row.status]}</span>
            <ChevronRight size={16} />
          </button>
        </li>)}</ul> : <Empty>{filter === 'all' ? '아직 연계 요청이 없습니다.' : '이 상태의 연계 요청이 없습니다.'}</Empty>}
        <Pagination page={page} pages={requests.data.totalPages} onPage={next => { setPage(next); setSelection(undefined) }} />
      </section>
      {selected && <ReferralDetail key={selected.requestId} request={selected} token={session.accessToken}
        canHandle={Boolean(canHandle)} onFulfill={onFulfill}
        onRead={() => { requests.retry(); onAlertsChanged() }}
        onUpdated={message => { setFilter('all'); setPage(0); setNotice(message); requests.retry() }} />}
    </div>}
  </section>
}

function ReferralDetail({ request, token, canHandle, onFulfill, onUpdated, onRead }: {
  request: ReferralRequest; token: string; canHandle: boolean
  onFulfill: (request: ReferralRequest) => void; onUpdated: (message: string) => void; onRead: () => void
}) {
  const today = seoulDate()
  const earliestDate = request.requestedDate > today ? request.requestedDate : today
  const [plannedDate, setPlannedDate] = useState(request.plannedDate && request.plannedDate >= earliestDate ? request.plannedDate : earliestDate)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function updateSchedule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    await updateRequest('plan')
  }

  async function updateRequest(action: 'plan' | 'unplan' | 'read') {
    if (busy) return
    setBusy(true); setError('')
    try {
      await api(`/test-requests/${request.requestId}/${action}`, token, {
        method: 'PATCH', body: action === 'plan' ? JSON.stringify({ plannedDate }) : undefined,
      })
      if (action === 'read') onRead()
      else onUpdated(action === 'plan' ? '방문 일정을 저장했습니다.' : '방문 일정을 취소했습니다.')
    } catch (failure) {
      setError((failure as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return <aside className="detail-card referral-detail" aria-label="연계 요청 상세">
    <div className="spread"><h2>{request.testType.name}</h2><span className="badge">{labels[request.status]}</span></div>
    <p className="muted">{request.place.name} · {request.place.dongName}</p>
    <dl className="detail-grid">
      <dt>보낸 기관</dt><dd>{request.requestedByOrganization.name}</dd>
      <dt>받는 기관</dt><dd>{request.recipientOrganization?.name ?? '—'}</dd>
      <dt>요청일</dt><dd>{dateLabel(request.requestedDate)}</dd>
      <dt>처리 건수</dt><dd>{request.completedCount} / {request.requestedCount}건</dd>
      {request.plannedDate && <><dt>방문 예정일</dt><dd>{dateLabel(request.plannedDate)}</dd></>}
      {request.completedDate && <><dt>완료일</dt><dd>{dateLabel(request.completedDate)}</dd></>}
    </dl>
    {request.note && <div className="detail-memo"><strong>공유 메모</strong><p>{request.note}</p></div>}
    {request.unread && <div className="actions"><button type="button" className="secondary" disabled={busy}
      onClick={() => void updateRequest('read')}><Bell size={15} />알림 확인</button></div>}
    <Status error={error} />
    {canHandle && request.status !== 'DONE' && <form className="referral-schedule" onSubmit={updateSchedule}>
      <div className="field"><label htmlFor="referral-planned-date"><CalendarDays size={15} /> 방문 예정일</label>
        <input id="referral-planned-date" type="date" min={earliestDate} value={plannedDate} required disabled={busy}
          onChange={event => setPlannedDate(event.target.value)} />
      </div>
      <div className="actions">
        <button type="submit" className="primary" disabled={busy}>방문 일정 저장</button>
        {request.status === 'PLANNED' && <>
          <button type="button" className="secondary" disabled={busy} onClick={() => void updateRequest('unplan')}>일정 취소</button>
          <button type="button" className="secondary" disabled={busy} onClick={() => onFulfill(request)}>처리 기록 작성</button>
        </>}
      </div>
    </form>}
  </aside>
}
