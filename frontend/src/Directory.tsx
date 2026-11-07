import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { CalendarDays, ChevronRight, Clock3, Copy, Download, ExternalLink, MapPin, Pencil, Search, ShieldCheck } from 'lucide-react'
import { dateLabel, inSeoul, normalized, readAllPages, seoulDate, timeLabel, useRemote, type Contact, type Dong, type Place, type Session, type Visit } from './api'
import { Empty, Pagination, Pill, Status } from './ui'
import { VisitDetails } from './VisitFlow'

const pageSize = 6
function SmallSearch({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  return <div className="small-search"><Search size={16} /><input type="search" aria-label={label} placeholder={label}
    value={value} onChange={e => onChange(e.target.value)} /></div>
}

export function Contacts({ token }: { token: string }) {
  const contacts = useRemote<Contact[]>('/organization-contacts', token)
  const [category, setCategory] = useState('전체')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [selection, setSelection] = useState('')
  const [notice, setNotice] = useState('')
  const filtered = contacts.data?.filter(row =>
    (category === '전체' || row.category === category || (category === '보건지소' && row.category === '보건진료소')
      || (category === '치매센터' && row.category === '치매안심센터')) &&
    normalized(row.name + row.area + row.details + row.phone + row.address).includes(normalized(query))) ?? []
  const visible = filtered.slice(page * pageSize, (page + 1) * pageSize)
  const contact = visible.find(row => row.id === selection) ?? visible[0]
  function reset() { setSelection(''); setPage(0); setNotice('') }
  async function copy(phone: string) {
    try { await navigator.clipboard.writeText(phone); setNotice('전화번호를 복사했습니다.') }
    catch { setNotice('복사할 수 없습니다. 표시된 전화번호를 선택해 복사해 주세요.') }
  }
  return <section className="directory-panel contacts-panel" aria-label="기관별 연락처">
    <div className="directory-toolbar">
      <div className="filters">{['전체', '보건소', '보건지소', '치매센터'].map(name =>
        <Pill key={name} selected={category === name} onClick={() => { setCategory(name); reset() }}>{name}</Pill>)}</div>
      <SmallSearch label="기관명 또는 담당 업무 검색" value={query} onChange={value => { setQuery(value); reset() }} />
    </div>
    <Status {...contacts} />
    {contacts.data && <div className="directory-layout">
      <section className="directory-list">
        <div className="spread list-title"><h1>기관 목록 <span>{filtered.length}</span></h1><small>안산시 공개 연락처</small></div>
        <div className="list-heading contact-columns"><span>기관명 / 구분</span><span>문의 전화</span><span>운영 시간</span><span /></div>
        {visible.length === 0 ? <Empty>조건에 맞는 기관이 없습니다.</Empty> :
          <ul className="directory-rows">{visible.map(row => <li key={row.id}>
            <button type="button" className="contact-columns" aria-pressed={contact?.id === row.id}
              onClick={() => { setSelection(row.id); setNotice('') }}>
              <span><strong>{row.name}</strong><small>{row.category} · {row.area}</small></span>
              <span>{row.phone}</span><small>{row.hours}</small><ChevronRight size={16} />
            </button></li>)}</ul>}
        <Pagination page={page} pages={Math.ceil(filtered.length / pageSize)} onPage={n => { setPage(n); setSelection(''); setNotice('') }} />
      </section>
      {contact && <aside className="detail-card contact-card" aria-label={contact.name + ' 연락처 상세'}>
        <h2>{contact.name}</h2><p className="muted">지역 주민의 건강을 함께 돌봅니다.</p>
        <div className="contact-phone"><small>문의 전화</small><a href={'tel:' + contact.phone}>{contact.phone}</a></div>
        <div className="contact-info"><MapPin size={17} /><div><small>주소</small><p>{contact.address}</p></div></div>
        <div className="contact-info"><Clock3 size={17} /><div><small>운영 시간</small><p>{contact.hours}</p>
          {contact.hours.startsWith('평일') && <p>점심시간 12:00–13:00<br />토·일요일 및 공휴일 휴무</p>}</div></div>
        <div className="contact-duties"><strong>주요 담당 업무</strong><p>{contact.details}</p></div>
        <div className="actions">
          <button className="secondary" type="button" onClick={() => void copy(contact.phone)}><Copy size={15} />전화번호 복사</button>
          <a className="secondary" href={'https://map.kakao.com/?q=' + encodeURIComponent(contact.address)} target="_blank" rel="noreferrer"><MapPin size={15} />지도 보기</a>
        </div>
        <a className="source-link" href={contact.source} target="_blank" rel="noreferrer">안산시 공식 안내 <ExternalLink size={12} /></a>
        <div className="contact-referral">
          <strong>보건소 연계</strong>
          <p>방문을 마친 시설의 새 기록에서 지원 항목과 받을 기관을 지정해 연계 요청을 보낼 수 있습니다.</p>
          <a className="source-link" href="#/map">지도에서 시설 선택 <ChevronRight size={14} /></a>
        </div>
        {notice && <p className="copy-notice" role="status">{notice}</p>}
      </aside>}
    </div>}
  </section>
}

export function Records({ session, places, revision, onEdit }: {
  session: Session; places: Place[]; revision: number; onEdit: (visit: Visit) => void
}) {
  const [rows, setRows] = useState<Visit[]>()
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  const [query, setQuery] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [page, setPage] = useState(0)
  const [selection, setSelection] = useState<number>()
  const token = session.accessToken
  const organization = session.user.organization.id
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    readAllPages<Visit>('/visits?organizationId=' + organization, token, controller.signal).then(
      data => { if (!controller.signal.aborted) setRows(data) },
      e => { if (!controller.signal.aborted) setError(e.message) },
    )
    return () => controller.abort()
  }, [token, revision, retry, organization])
  const byPlace = new Map(places.map(p => [p.placeId, p]))
  const filtered = rows?.filter(row => (!fromDate || row.visitDate >= fromDate) && (!toDate || row.visitDate <= toDate)
    && normalized((byPlace.get(row.placeId)?.name || '') + row.memo + row.performedTests.map(test => test.name).join('')).includes(normalized(query))) ?? []
  const pages = Math.ceil(filtered.length / pageSize)
  const current = Math.min(page, Math.max(0, pages - 1))
  const visible = filtered.slice(current * pageSize, (current + 1) * pageSize)
  const selected = visible.find(row => row.visitId === selection) ?? visible[0]
  const facility = selected ? byPlace.get(selected.placeId) : undefined
  const month = seoulDate().slice(0, 7)
  const stats = [
    ['전체 기록', rows?.length, '건'],
    ['이번 달 작성', rows?.filter(row => inSeoul(new Date(row.createdAt)).startsWith(month)).length, '건'],
    ['방문 시설', rows ? new Set(rows.map(row => row.placeId)).size : undefined, '곳'],
  ]

  function download(row: Visit) {
    const content = [facility?.name, facility?.address, '방문 일시: ' + row.visitDate + ' ' + timeLabel(row.visitTime),
      '방문 기관: ' + row.organization.name, '검사 내용: ' + row.performedTests.map(test => `${test.name} ${test.count}건`).join(', '),
      '', row.memo].filter(value => value !== undefined).join('\n')
    const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url; link.download = `안부-방문기록-${row.visitDate}-${row.visitId}.txt`; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <section className="directory-panel records-panel" aria-label="방문 기록">
    <div className="stats">{stats.map(([label, value, unit]) =>
      <div key={label}><span>{label}</span><span><strong>{value ?? '—'}</strong> <small>{unit}</small></span></div>)}</div>
    <div className="directory-toolbar">
      <span className="badge">완료된 방문</span>
      <div className="record-search-controls">
        <div className="date-filter"><CalendarDays size={15} />
          <input aria-label="시작일" type="date" value={fromDate} max={toDate || undefined} onChange={e => { setFromDate(e.target.value); setPage(0) }} />
          <span>–</span><input aria-label="종료일" type="date" value={toDate} min={fromDate || undefined}
            onChange={e => { setToDate(e.target.value); setPage(0) }} />
        </div>
        <SmallSearch label="경로당명 또는 내용 검색" value={query} onChange={value => { setQuery(value); setPage(0) }} />
      </div>
    </div>
    <Status error={error} loading={!rows} retry={() => setRetry(n => n + 1)} />
    {rows && <div className="directory-layout">
      <section className="directory-list">
        <div className="spread list-title"><h1>작성한 기록 <span>{filtered.length}</span></h1><small>최신 순</small></div>
        <div className="list-heading record-columns"><span>방문일</span><span>경로당 / 방문 내용</span><span>방문 기관</span><span>상태</span></div>
        {visible.length === 0 ? <Empty>{rows.length ? '검색 조건에 맞는 기록이 없습니다.' : '작성한 방문 기록이 없습니다. 지도에서 시설을 선택해 기록을 작성하세요.'}</Empty> :
          <ul className="directory-rows">{visible.map(row => <li key={row.visitId}>
            <button type="button" className="record-columns" aria-pressed={selected?.visitId === row.visitId}
              onClick={() => setSelection(row.visitId)}>
              <span>{dateLabel(row.visitDate)}</span><span><strong>{byPlace.get(row.placeId)?.name || '방문 시설'}</strong>
                <small>{row.performedTests.map(test => test.name).join(', ') || '방문 기록'}</small></span>
              <small>{row.organization.name}</small><span className="badge">완료</span>
            </button></li>)}</ul>}
        <Pagination page={current} pages={pages} onPage={n => { setPage(n); setSelection(undefined) }} />
      </section>
      {selected && <aside className="detail-card" aria-label="방문 기록 상세">
        <h2>{facility?.name || '방문 시설'}</h2><p className="muted">{facility?.address}</p>
        <hr /><VisitDetails visit={selected} organizationId={organization} />
        <div className="actions">
          <button className="secondary" type="button" onClick={() => onEdit(selected)}><Pencil size={15} />기록 수정</button>
          <button className="secondary" type="button" onClick={() => download(selected)}><Download size={15} />기록 내려받기</button>
        </div>
      </aside>}
    </div>}
  </section>
}

type Preferences = { upcoming: boolean; incomplete: boolean; news: boolean }
const defaultPreferences: Preferences = { upcoming: true, incomplete: true, news: false }
function storedPreferences(key: string): Preferences {
  try { return { ...defaultPreferences, ...JSON.parse(localStorage.getItem(key) ?? '{}') } }
  catch { return defaultPreferences }
}
function ProfileField({ label, children }: { label: string; children: ReactNode }) {
  return <div className="field"><span>{label}</span><div className="readonly-field">{children}</div></div>
}
export function Settings({ session, dongs }: { session: Session; dongs: Dong[] }) {
  const key = 'anbu.preferences.' + session.user.id
  const [prefs, setPrefs] = useState(() => storedPreferences(key))
  const [status, setStatus] = useState('')
  const organizations = useRemote<{ id: number; managedDongIds: number[] }[]>('/organizations', session.accessToken)
  const contacts = useRemote<Contact[]>('/organization-contacts', session.accessToken)
  const managed = organizations.data?.find(org => org.id === session.user.organization.id)?.managedDongIds ?? []
  const responsible = dongs.filter(dong => managed.includes(dong.id)).map(dong => [dong.gu, dong.name].filter(Boolean).join(' ')).join(', ')
  const contact = contacts.data?.find(row => normalized(row.name) === normalized(session.user.organization.name))
  function save(event: FormEvent) {
    event.preventDefault()
    try { localStorage.setItem(key, JSON.stringify(prefs)); setStatus('이 브라우저에 설정을 저장했습니다.') }
    catch { setStatus('브라우저에 설정을 저장할 수 없습니다.') }
  }
  const options: [keyof Preferences, string, string][] = [
    ['upcoming', '다음 방문 일정 알림', '방문 예정일 알림에 대한 선호를 설정합니다.'],
    ['incomplete', '임시 저장 기록 알림', '작성 중인 기록 알림에 대한 선호를 설정합니다.'],
    ['news', '서비스 공지 이메일', '새 기능과 서비스 소식 수신 선호를 설정합니다.'],
  ]
  return <form className="settings-page" onSubmit={save} aria-label="설정">
    <div className="settings-layout">
      <section>
        <div className="spread"><h1>내 정보</h1><span className="badge">기관 담당자</span></div>
        <div className="form-row profile-fields">
          <ProfileField label="이름">{session.user.name}</ProfileField>
          <ProfileField label="소속 기관">{session.user.organization.name}</ProfileField>
          <ProfileField label="업무 연락처">{contact?.phone ?? '등록 정보 없음'}</ProfileField>
        </div>
        <p className="managed"><MapPin size={17} />담당 지역 <strong>{responsible || '등록된 담당 지역 없음'}</strong></p>
        <p className="muted">계정 정보와 담당 지역 변경은 소속 기관 관리자에게 문의해 주세요.</p>
        <hr /><h2>알림 설정</h2>
        {options.map(([name, title, text]) => <label className="toggle-row" key={name}>
          <span>{title}<small>{text}</small></span>
          <input type="checkbox" role="switch" checked={prefs[name]} onChange={e => { setStatus(''); setPrefs({ ...prefs, [name]: e.target.checked }) }} />
        </label>)}
      </section>
      <aside className="settings-aside">
        <section className="detail-card">
          <h2><ShieldCheck size={18} /> 계정 보안</h2>
          <strong>비밀번호</strong><p className="muted">비밀번호 변경이나 분실 시 소속 기관 관리자에게 문의해 주세요.</p>
          <hr /><strong>현재 로그인</strong><p>{session.user.organization.name}</p>
        </section>
        <section className="detail-card">
          <h2>서비스 안내</h2>
          <a href="#/contacts">기관별 연락처 <ChevronRight size={16} /></a>
          {contact && <a href={contact.source} target="_blank" rel="noreferrer">소속 기관 안내 <ExternalLink size={14} /></a>}
          <p className="muted">서비스 버전 0.1.0</p>
        </section>
      </aside>
    </div>
    <hr />
    <footer className="form-footer">
      <p role="status">{status || '알림 선호는 이 브라우저에 저장됩니다. 알림 발송은 아직 연결되어 있지 않습니다.'}</p>
      <div className="actions"><button className="secondary" type="button" onClick={() => { setPrefs(storedPreferences(key)); setStatus('') }}>취소</button>
        <button className="primary" type="submit">변경사항 저장</button></div>
    </footer>
  </form>
}
