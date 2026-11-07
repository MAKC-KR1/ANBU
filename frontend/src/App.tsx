import { useEffect, useState, type FormEvent } from 'react'
import { Building2, Eye, EyeOff, Link2, LockKeyhole, Map as MapIcon, MapPin, NotebookText, Phone, Search, Settings2, UserRound } from 'lucide-react'
import { api, isPreview, normalized, restoreSession, storeSession, useRemote, type Dong, type Place,
  type ReferralDirection, type ReferralRequest, type Session, type TestType, type Visit } from './api'
import { MapScreen } from './MapScreen'
import { FacilityDrawer } from './VisitFlow'
import { Contacts, Records, Settings } from './Directory'
import { Referrals } from './Referrals'
import { Status } from './ui'

const clinic = '대부남동보건진료소'
const previewOrganizations = [clinic, '단원보건소', '상록수보건소', '대부보건지소']
type Route = { screen: 'map' | 'contacts' | 'records' | 'requests' | 'settings'; dongId?: number; placeId?: number; direction?: ReferralDirection }
function readRoute(): Route {
  const [screen, dong, place] = location.hash.slice(2).split('/')
  if (screen === 'requests') return { screen, direction: dong === 'outgoing' ? 'outgoing' : 'incoming' }
  if (screen === 'contacts' || screen === 'records' || screen === 'settings') return { screen }
  return { screen: 'map', dongId: /^[1-9]\d*$/.test(dong) ? Number(dong) : undefined, placeId: /^[1-9]\d*$/.test(place) ? Number(place) : undefined }
}
function navigate(screen: Route['screen'], dongId?: number, placeId?: number) {
  const hash = '#/' + [screen, dongId, placeId].filter(value => value !== undefined).join('/')
  if (location.hash !== hash) location.hash = hash
}

function Login({ onLogin, expired }: { onLogin: (session: Session) => void; expired: boolean }) {
  const [loginId, setLoginId] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const previewOrganization = previewOrganizations.find(name => name === loginId.trim()) ?? clinic
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const values = new FormData(event.currentTarget)
    setBusy(true); setError(''); setMessage('')
    try {
      const response = await api<Omit<Session, 'expiresAt'> & { expiresIn: number }>('/auth/login', undefined, {
        method: 'POST', body: JSON.stringify({ loginId: String(values.get('loginId')).trim(), password: values.get('password') }),
      })
      onLogin({ user: response.user, accessToken: response.accessToken, expiresAt: Date.now() + response.expiresIn * 1000 })
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }
  return <main className="login-page">
    <div className="login-art">
      <img src={import.meta.env.BASE_URL + 'login-illustration.png'} width="1178" height="1900"
        alt="함께하는 안산시 방문형 보건 서비스, 안부" />
    </div>
    <div className="login-side">
      <form className="login-form" onSubmit={submit}>
        <span className="badge login-badge">안산시 보건소 · 보건지소 용</span>
        <h1>로그인</h1>
        <p className="muted login-lead">소속 기관의 계정으로 로그인하세요.</p>
        <div className="login-fields">
          <div className="login-field">
            <label htmlFor="organization">소속 기관</label>
            <div className="icon-input"><Building2 size={18} /><select id="organization" value={isPreview ? previewOrganization : ''}
              onChange={event => setLoginId(event.target.value)}>
              {isPreview ? previewOrganizations.map(name => <option key={name}>{name}</option>) :
                <option value="">로그인 후 계정의 소속 기관으로 연결됩니다</option>}
            </select></div>
          </div>
          <div className="login-field">
            <label htmlFor="login-id">아이디</label>
            <div className="icon-input"><UserRound size={18} /><input id="login-id" name="loginId" required maxLength={100}
              autoComplete="username" value={loginId} onChange={event => setLoginId(event.target.value)}
              placeholder={isPreview ? clinic : '기관 계정 아이디'} /></div>
          </div>
          <div className="login-field">
            <label htmlFor="login-password">비밀번호</label>
            <div className="icon-input"><LockKeyhole size={18} /><input id="login-password" name="password" required maxLength={72}
              autoComplete="current-password" type={showPassword ? 'text' : 'password'} placeholder="비밀번호 입력" />
              <button className="icon-button" type="button" onClick={() => setShowPassword(s => !s)} aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 표시'}>
                {showPassword ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            </div>
          </div>
        </div>
        <div className="login-feedback">
          {error ? <p className="error" role="alert">{error}</p> : message ? <p role="status">{message}</p> :
            expired ? <p role="status">로그인 시간이 만료되었습니다. 다시 로그인해 주세요.</p> : null}
        </div>
        <button type="submit" className="primary login-submit" disabled={busy}>{busy ? '로그인 중…' : '로그인'}</button>
        <div className="login-links">{['ID찾기', 'PW찾기', '계정신청'].map(label => <button type="button" key={label}
          onClick={() => { setError(''); setMessage('계정 관련 문의는 소속 기관 관리자에게 연락해 주세요.') }}>{label}</button>)}</div>
      </form>
    </div>
  </main>
}

function PlaceSearch({ places, onSelect }: { places: Place[]; onSelect: (place: Place) => void }) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(false)
  const results = normalized(query) ? places.filter(p => normalized(p.name + ' ' + p.address).includes(normalized(query))).slice(0, 8) : []
  const show = active && Boolean(query.trim())
  function select(place: Place) { onSelect(place); setActive(false); setQuery('') }
  return <form className="place-search" role="search" onSubmit={e => { e.preventDefault(); if (results[0]) select(results[0]) }}
    onBlur={e => { if (!e.currentTarget.contains(e.relatedTarget)) setActive(false) }}>
    <div className="search-field"><button type="submit" aria-label="시설 검색"><Search size={23} /></button>
      <input aria-label="경로당명 또는 주소 검색" value={query} onChange={e => { setQuery(e.target.value); setActive(true) }}
        onFocus={() => setActive(true)} onKeyDown={e => { if (e.key === 'Escape') setActive(false) }} placeholder="경로당명 또는 주소 검색" /></div>
    {show && <div className="search-results">
      {results.length === 0 ? <p>검색 결과가 없습니다.</p> : results.map(p => <button type="button" key={p.placeId} onClick={() => select(p)}>
        <MapPin size={17} /><span><strong>{p.name}</strong><small>{p.address}</small></span>
      </button>)}
    </div>}
  </form>
}

function Workspace({ session, logout }: { session: Session; logout: () => void }) {
  const [route, setRoute] = useState(readRoute)
  const [revision, setRevision] = useState(0)
  const [editing, setEditing] = useState<Visit>()
  const [fulfillment, setFulfillment] = useState<ReferralRequest>()
  const [alertRevision, setAlertRevision] = useState(0)
  const [unread, setUnread] = useState(0)
  const token = session.accessToken
  const dongs = useRemote<Dong[]>('/dongs', token)
  const places = useRemote<Place[]>('/map/places', token)
  const tests = useRemote<TestType[]>('/test-types', token)
  const place = places.data?.find(p => p.placeId === route.placeId && p.dongId === route.dongId)

  useEffect(() => {
    const read = () => { setRoute(readRoute()); setEditing(undefined); setFulfillment(undefined) }
    addEventListener('hashchange', read)
    return () => removeEventListener('hashchange', read)
  }, [])
  useEffect(() => { window.scrollTo(0, 0) }, [route.screen, route.dongId])
  useEffect(() => {
    let pending: AbortController | undefined
    const refresh = () => {
      pending?.abort()
      const controller = new AbortController()
      pending = controller
      void api<{ count: number }>('/test-requests/unread-count', token, { signal: controller.signal }).then(
        ({ count }) => { if (!controller.signal.aborted) setUnread(count) }, () => {},
      )
    }
    refresh()
    const timer = window.setInterval(() => { if (!document.hidden) refresh() }, 15_000)
    window.addEventListener('focus', refresh)
    return () => { pending?.abort(); window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [token, alertRevision])
  const nav = [
    ['map', '지도', MapIcon], ['contacts', '기관별 연락처', Phone],
    ['records', '방문 기록', NotebookText], ['requests', '연계 요청', Link2], ['settings', '설정', Settings2],
  ] as const
  const selectPlace = (p: Place) => navigate('map', p.dongId, p.placeId)
  const isCityMap = route.screen === 'map' && !route.dongId

  return <div className={`app-shell${isCityMap ? ' app-shell-city' : ''}`}>
    <aside className="sidebar">
      <a className="wordmark" href="#/map" aria-label="안부 지도">
        <img src={import.meta.env.BASE_URL + 'anbu-wordmark.svg'} width="58" height="27" alt="" />
      </a>
      <p className="identity">
        <strong>{session.user.name}</strong><span aria-hidden="true">·</span>
        <span className="organization-name" title={session.user.organization.name}>{session.user.organization.name}</span>
      </p>
      <nav aria-label="주 메뉴">{nav.map(([key, title, Icon]) => <a key={key} href={'#/' + key}
        title={title} aria-current={route.screen === key ? 'page' : undefined}
        aria-label={key === 'requests' && unread > 0 ? `연계 요청, 새 알림 ${unread}건` : undefined}>
        <Icon size={20} /><span>{title}</span>
        {key === 'requests' && unread > 0 && <span className="nav-referral-unread" aria-hidden="true">{unread > 99 ? '99+' : unread}</span>}
      </a>)}</nav>
      <span className="sr-only" aria-live="polite">{unread > 0 ? `새 연계 요청 알림 ${unread}건` : ''}</span>
      <div className="sidebar-footer">
        <button className="logout" type="button" onClick={logout}>로그아웃</button>
      </div>
    </aside>
    <main className={`workspace ${route.screen === 'map' ? 'workspace-map' : ''}${isCityMap ? ' workspace-city' : ''}`}>
      <header className="workspace-header">
        {route.screen !== 'settings' && <PlaceSearch places={places.data ?? []} onSelect={selectPlace} />}
        <span className="compact-organization organization-name" title={session.user.organization.name}>{session.user.organization.name}</span>
      </header>
      {route.screen === 'map' && <MapScreen token={token} dongId={route.dongId} dongs={dongs.data ?? []}
        places={places.data ?? []} tests={tests.data ?? []} revision={revision}
        onDong={id => navigate('map', id)} onPlace={selectPlace} />}
      {route.screen === 'contacts' && <Contacts token={token} />}
      {route.screen === 'records' && <Records session={session} places={places.data ?? []} revision={revision} onEdit={setEditing} />}
      {route.screen === 'requests' && <Referrals key={route.direction} direction={route.direction ?? 'incoming'}
        session={session} tests={tests.data ?? []} revision={revision} onFulfill={setFulfillment}
        onAlertsChanged={() => setAlertRevision(value => value + 1)} />}
      {route.screen === 'settings' && <Settings session={session} dongs={dongs.data ?? []} />}
      {(dongs.error || places.error || tests.error) && <Status error={dongs.error || places.error || tests.error}
        retry={() => { dongs.retry(); places.retry(); tests.retry() }} />}
    </main>
    {place && <FacilityDrawer key={place.placeId} place={place} session={session} tests={tests.data ?? []}
      onClose={() => navigate('map', place.dongId)} onSaved={() => setRevision(r => r + 1)} />}
    {editing && <FacilityDrawer key={'edit' + editing.visitId} place={places.data?.find(p => p.placeId === editing.placeId)}
      session={session} tests={tests.data ?? []} edit={editing} onClose={() => setEditing(undefined)}
      onSaved={() => { setRevision(r => r + 1); setEditing(undefined) }} />}
    {fulfillment && <FacilityDrawer key={'fulfill' + fulfillment.requestId}
      place={places.data?.find(p => p.placeId === fulfillment.place.id)} fulfillment={fulfillment}
      session={session} tests={tests.data ?? []} onClose={() => setFulfillment(undefined)}
      onSaved={() => { setRevision(r => r + 1); setFulfillment(undefined) }} />}
  </div>
}

export function App() {
  const [session, setSession] = useState<Session | null>(restoreSession)
  const [expired, setExpired] = useState(false)
  function logout() { storeSession(null); setSession(null); setExpired(false) }
  useEffect(() => {
    if (!session) return
    const expire = () => { storeSession(null); setSession(null); setExpired(true) }
    const timeout = setTimeout(expire, Math.max(0, session.expiresAt - Date.now()))
    addEventListener('anbu:expired', expire)
    return () => { clearTimeout(timeout); removeEventListener('anbu:expired', expire) }
  }, [session])

  return session ? <Workspace session={session} logout={logout} /> : <Login expired={expired} onLogin={loggedIn => {
    navigate('map'); storeSession(loggedIn); setSession(loggedIn); setExpired(false)
  }} />
}
