import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowDownWideNarrow, Bell, CalendarCheck, CalendarDays, Check, ChevronDown, Clock3, Info, Link2, MapPin, Plus, Search } from 'lucide-react'
import { api, dateLabel, normalized, resultLabels, seoulDate, timeLabel, useRemote, type Page, type Place,
  type ReferralRequest, type Reservation, type ResultCode, type Session, type TestType, type Visit } from './api'
import { Drawer, Empty, Pagination, Status } from './ui'

type Mode = 'detail' | 'reservation' | 'record'
type Props = {
  place?: Place; session: Session; tests: TestType[]; edit?: Visit; fulfillment?: ReferralRequest
  onClose: () => void; onSaved: () => void
}

const heungseongPhotos = [
  ['01.svg', '경로당 내부의 테이블과 의자'],
  ['02.svg', '경로당 실내에서 진행한 어르신 활동'],
  ['03.svg', '케이크를 앞에 둔 경로당 행사'],
  ['04.svg', '경로당 실내 단체 사진'],
] as const

function visitSubject(visit: Visit) {
  return visit.memo.match(/^방문 목적:[ \t]*([^\r\n]+)/)?.[1].trim()
    || visit.performedTests.map(test => test.name).join(', ') || '방문'
}

export function FacilityDrawer({ place, session, tests, edit, fulfillment, onClose, onSaved }: Props) {
  const [mode, setMode] = useState<Mode>(edit || fulfillment ? 'record' : 'detail')
  const [tab, setTab] = useState<'records' | 'reservations'>('records')
  const [revision, setRevision] = useState(0)
  const [page, setPage] = useState(0)
  const [selection, setSelection] = useState<number>()
  const [search, setSearch] = useState('')
  const [notice, setNotice] = useState<{ message: string; referralSent: boolean }>()
  const token = session.accessToken
  const id = fulfillment?.place.id ?? edit?.placeId ?? place?.placeId
  const bookings = useRemote<Reservation[]>(!edit && !fulfillment && id ? '/visit-reservations?placeId=' + id : null, token, revision)
  const visits = useRemote<Page<Visit>>(!edit && !fulfillment && id ? '/visits?placeId=' + id + '&page=' + page + '&size=30' : null, token, revision)
  const isReservation = mode === 'reservation'
  const facility = fulfillment?.place.name ?? place?.name ?? '방문 시설'
  const title = mode === 'detail' ? facility + ' 방문 예약 및 방문 기록'
    : isReservation ? '방문 예약' : fulfillment ? '연계 요청 처리' : edit ? '방문 기록 수정' : '방문 기록 작성'

  function open(next: Mode) { setNotice(undefined); setMode(next) }
  function done(createdId?: number, recipientName?: string) {
    if (edit || fulfillment) { onSaved(); return }
    setTab(isReservation ? 'reservations' : 'records')
    setSelection(createdId)
    setPage(0); setSearch('')
    setNotice({
      message: recipientName ? `방문 기록을 저장하고 ${recipientName}에 연계 요청과 앱 알림을 보냈습니다.`
        : isReservation ? '방문 예약이 저장되었습니다.' : '방문 기록이 저장되었습니다.',
      referralSent: Boolean(recipientName),
    })
    setRevision(n => n + 1); setMode('detail'); onSaved()
  }
  function changeTab(next: typeof tab) { setTab(next); setSearch(''); setSelection(undefined) }

  const filteredBookings = bookings.data?.filter(row => normalized(row.organizationName + row.testTypeName + row.visitDate).includes(normalized(search))) ?? []
  const filteredVisits = visits.data?.content.filter(row => normalized(row.organization.name + row.memo
    + row.performedTests.map(test => test.name).join('') + row.visitDate).includes(normalized(search))) ?? []
  const selectedBooking = filteredBookings.find(row => row.id === selection) ?? filteredBookings[0]
  const selectedVisit = filteredVisits.find(row => row.visitId === selection) ?? filteredVisits[0]

  return <Drawer title={title} onClose={onClose} className={mode === 'detail' ? 'facility-drawer' : 'form-drawer'}>
    {mode !== 'detail' && id ? <VisitForm key={mode} placeId={id} facility={facility} session={session}
      tests={tests} reservation={isReservation} edit={edit} fulfillment={fulfillment} onSaved={done}
      onCancel={edit || fulfillment ? onClose : () => setMode('detail')} /> : <>
      <header className="facility-header">
        <div><h1>{facility}</h1><p><MapPin size={15} />{place?.address}</p></div>
        <div className="actions">
          <div className="small-search facility-search"><Search size={15} /><input type="search" aria-label="시설 방문 목록 검색" value={search}
            onChange={event => setSearch(event.target.value)} placeholder="기관명 또는 검사 항목 검색" /></div>
          <button className="primary orange" type="button" onClick={() => open('reservation')}>방문 예약</button>
          <button className="primary" type="button" onClick={() => open('record')}><Plus size={17} />새 기록 작성</button>
        </div>
      </header>
      {notice && <p className="success" role="status"><Check size={17} />{notice.message}
        {notice.referralSent && <a className="visit-referral-link" href="#/requests/outgoing">보낸 요청 보기</a>}
      </p>}
      <div className="facility-layout">
        <div className="facility-detail">
          {facility === '흥성마을경로당' && <section className="facility-gallery" aria-label="흥성마을경로당 사진">
            {heungseongPhotos.map(([file, alt]) => <img key={file} alt={alt}
              src={import.meta.env.BASE_URL + 'heungseong/' + file} width="158" height="145" />)}
          </section>}
          <section className="detail-card">
            {tab === 'reservations' ? <BookingDetails booking={selectedBooking} />
              : <VisitDetails visit={selectedVisit} organizationId={session.user.organization.id} />}
          </section>
        </div>
        <section className="facility-list" aria-label="시설 방문 목록">
          <div className="facility-list-header">
            <div className="facility-list-tabs" role="group" aria-label="목록 종류">
              <button type="button" aria-pressed={tab === 'records'} onClick={() => changeTab('records')}>방문 이력</button>
              <button type="button" aria-pressed={tab === 'reservations'} onClick={() => changeTab('reservations')}>방문 예약</button>
            </div>
            {tab === 'records' && <span className="facility-list-sort"><ArrowDownWideNarrow aria-hidden="true" size={17} />최신 순</span>}
          </div>
          <Status {...(tab === 'records' ? visits : bookings)} />
          {tab === 'reservations' && bookings.data && (
            filteredBookings.length ? <ul className="facility-rows">{filteredBookings.map(row => <li key={row.id}>
              <button type="button" aria-pressed={selectedBooking?.id === row.id} className="reservation-row"
                onClick={() => setSelection(row.id)}>
                <span><small>{dateLabel(row.visitDate)} {timeLabel(row.visitTime)}</small><strong>{row.testTypeName}</strong>
                  <small>{row.organizationName}</small></span><span className="badge badge-orange">예약</span>
              </button></li>)}</ul> : <Empty>{search ? '검색 결과가 없습니다.' : '등록된 방문 예약이 없습니다.'}</Empty>
          )}
          {tab === 'records' && visits.data && <>
            {filteredVisits.length ? <ul className="facility-rows">{filteredVisits.map(row => <li key={row.visitId}>
              <button type="button" aria-pressed={selectedVisit?.visitId === row.visitId} className="visit-history-row"
                onClick={() => setSelection(row.visitId)}>
                <time dateTime={row.visitDate}>{dateLabel(row.visitDate)}</time><span className="badge">완료</span>
                <span className="visit-history-subject"><strong>{visitSubject(row)}</strong><small>{row.organization.name}</small></span>
              </button></li>)}</ul> : <Empty>{search ? '검색 결과가 없습니다.' : '등록된 방문 기록이 없습니다.'}</Empty>}
            <Pagination page={page} pages={visits.data.totalPages} onPage={n => { setPage(n); setSelection(undefined) }} />
          </>}
        </section>
      </div>
    </>}
  </Drawer>
}

function BookingDetails({ booking }: { booking?: Reservation }) {
  if (!booking) return <div className="detail-empty"><CalendarCheck size={30} /><h2>방문 예약</h2><p>이 시설에 등록된 방문 예약을 확인할 수 있습니다.</p></div>
  return <>
    <div className="spread"><h2>예약 상세</h2><span className="badge badge-orange">예약</span></div>
    <dl className="detail-grid">
      <dt>방문 예정 일시</dt><dd>{dateLabel(booking.visitDate)} {timeLabel(booking.visitTime)}</dd>
      <dt>방문 기관</dt><dd>{booking.organizationName}</dd>
      <dt>예정 검사</dt><dd>{booking.testTypeName}</dd>
    </dl>
  </>
}

export function VisitDetails({ visit, organizationId }: { visit?: Visit; organizationId?: number }) {
  if (!visit) return <div className="detail-empty"><CalendarDays size={30} /><h2>방문 기록</h2><p>이 시설에 저장된 기록이 여기에 표시됩니다.</p></div>
  return <>
    <div className="spread"><h2>방문 요약</h2><span className="badge">완료</span></div>
    <dl className="detail-grid">
      <dt>방문 일시</dt><dd>{dateLabel(visit.visitDate)} {timeLabel(visit.visitTime)}</dd>
      <dt>방문 기관</dt><dd>{visit.organization.name}</dd>
      <dt>검사 내용</dt><dd>{visit.performedTests.map(test => `${test.name} ${test.count}건`).join(', ') || '—'}</dd>
      <dt>검사 결과</dt><dd className="accent">{visit.resultCode ? resultLabels[visit.resultCode] : '—'}</dd>
    </dl>
    <div className="detail-memo"><strong>결과 상세</strong><p>{visit.memo || '작성된 내용이 없습니다.'}</p></div>
    {visit.organization.id === organizationId && visit.createdRequestIds.length > 0 &&
      <div className="detail-memo spread"><strong>연계 현황</strong>
        <a className="visit-referral-link" href="#/requests/outgoing">연계 현황 보기</a>
      </div>}
  </>
}

type FormProps = {
  placeId: number; facility: string; session: Session; tests: TestType[]; reservation: boolean
  edit?: Visit; fulfillment?: ReferralRequest; onSaved: (id?: number, recipientName?: string) => void; onCancel: () => void
}
type ReferralDraft = { testTypeId?: number; recipientOrganizationId?: number; count: string; note: string }
const emptyReferral: ReferralDraft = { count: '1', note: '' }

function VisitForm({ placeId, facility, session, tests, reservation, edit, fulfillment, onSaved, onCancel }: FormProps) {
  const [date, setDate] = useState(edit?.visitDate ?? seoulDate(reservation ? 1 : 0))
  const [time, setTime] = useState(timeLabel(edit?.visitTime) || '10:30')
  const [selected, setSelected] = useState<number[]>(edit?.performedTests.map(test => test.testTypeId) ?? [])
  const [counts, setCounts] = useState<Record<number, string>>(() => fulfillment
    ? { [fulfillment.testType.id]: String(Math.min(fulfillment.remainingCount, 9999)) }
    : Object.fromEntries(edit?.performedTests.map(test => [test.testTypeId, String(test.count)]) ?? []))
  const [showChips, setShowChips] = useState(false)
  const [result, setResult] = useState<ResultCode>(edit?.resultCode ?? 'GOOD')
  const [memo, setMemo] = useState(edit?.memo ?? '')
  const [referral, setReferral] = useState<ReferralDraft>()
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const referralButton = useRef<HTMLButtonElement>(null)
  const referralItem = useRef<HTMLSelectElement>(null)
  const organizationId = session.user.organization.id
  const memoOnly = Boolean(edit && (edit.createdRequestIds.length || edit.fulfillments.length))
  const eligibleTests = tests.filter(test => test.responsibleOrganizations.some(org => org.id === organizationId))
  const selectedTests = fulfillment ? [fulfillment.testType]
    : selected.map(id => eligibleTests.find(test => test.id === id)).filter((test): test is TestType => Boolean(test))
  const canRefer = !reservation && !edit && !fulfillment
  const referralOpen = Boolean(canRefer && result === 'FOLLOW_UP' && referral)
  const otherOrganizations = (test: TestType) => (test.referralOrganizations ?? []).filter(org => org.id !== organizationId)
  const referralTests = tests.filter(test => otherOrganizations(test).length > 0)
  const referralTest = referralTests.find(test => test.id === referral?.testTypeId)
  const recipients = referralTest ? otherOrganizations(referralTest) : []
  const recipient = recipients.find(org => org.id === referral?.recipientOrganizationId)

  useEffect(() => { if (referralOpen) referralItem.current?.focus() }, [referralOpen])

  function choose(id: number) {
    setSelected(ids => reservation ? [id] : ids.includes(id) ? ids.filter(old => old !== id) : [...ids, id])
    if (reservation) setShowChips(false)
    setError('')
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const request = referralOpen && referral && referralTest && recipient ? {
      testTypeId: referralTest.id, recipientOrganizationId: recipient.id, count: Number(referral.count),
      note: referral.note.trim() || undefined,
    } : undefined
    if (referralOpen && !request) { setError('지원 항목과 받을 기관을 선택해 주세요.'); return }
    if (!memoOnly && !selectedTests.length && !request) { setError('검사 항목을 선택해 주세요.'); setShowChips(true); return }
    if (!reservation && !memoOnly && !memo.trim()) { setError('결과 상세를 입력해 주세요.'); return }
    setBusy(true); setError('')
    const token = session.accessToken
    try {
      if (reservation) {
        const booked = await api<Reservation>('/visit-reservations', token, { method: 'POST',
          body: JSON.stringify({ placeId, visitDate: date, visitTime: time, testTypeId: selectedTests[0].id }) })
        onSaved(booked.id)
      } else {
        const performedTests = selectedTests.map(test => ({ testTypeId: test.id, count: Number(counts[test.id] ?? 1) }))
        const serviceCodes = selectedTests.map(test => test.serviceCode)
        if (request && referralTest) serviceCodes.push(referralTest.serviceCode)
        const payload = memoOnly ? { memo: memo.trim() } : {
          placeId, visitDate: date, visitTime: time, resultCode: result, memo: memo.trim(),
          serviceCodes: [...new Set(serviceCodes)], performedTests,
          ...(request && { requestedTests: [request] }),
          ...(fulfillment && { fulfilledRequests: [{ requestId: fulfillment.requestId, completedCount: performedTests[0].count }] }),
        }
        const visit = await api<{ visitId: number }>(edit ? '/visits/' + edit.visitId : '/visits', token, {
          method: edit ? 'PATCH' : 'POST', body: JSON.stringify(payload),
        })
        onSaved(visit.visitId, request ? recipient?.name : undefined)
      }
    } catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  return <form className={`visit-form${referralOpen ? ' visit-form-referral' : ''}`} onSubmit={submit}>
    <header><h1>{reservation ? '방문 예약' : fulfillment ? '연계 요청 처리' : edit ? '방문 기록 수정' : '방문 정보'}</h1>
      <p className="muted"><MapPin size={15} />{facility}</p>
    </header>
    <div className="visit-form-fields">
      {memoOnly && <p className="status">검사 요청 또는 처리 실적과 연결된 기록은 결과 상세만 수정할 수 있습니다.</p>}
      <fieldset disabled={busy || memoOnly} className="form-row">
        <div className="field">
          <label htmlFor="visit-date">{reservation ? '방문 예정 일시' : '방문 일시'} <b>*</b></label>
          <div className="datetime">
            <input id="visit-date" type="date" min={reservation ? seoulDate() : fulfillment?.requestedDate} max={!reservation ? seoulDate() : undefined}
              value={date} required onChange={e => setDate(e.target.value)} />
            <input aria-label="방문 시간" type="time" value={time} required onChange={e => setTime(e.target.value)} />
          </div>
        </div>
        <div className="field"><label htmlFor="visit-organization">방문 기관 <b>*</b></label>
          <input id="visit-organization" value={session.user.organization.name} readOnly />
        </div>
      </fieldset>
      <hr />
      <h2>{reservation ? '예정 검사 내용' : '검사 내용 및 결과'}</h2>
      <fieldset disabled={busy || memoOnly} className="form-row">
        <div className="field">
          <label id="tests-label">검사 항목 <b>*</b></label>
          {fulfillment ? <div className="readonly-field">{fulfillment.testType.name}</div>
            : <button className="select-button" type="button" aria-labelledby="tests-label" aria-expanded={showChips}
            aria-controls="test-chip-grid" onClick={() => setShowChips(current => !current)}>
            <span>{selectedTests.length ? selectedTests.map(t => t.name).join(', ') : '검사 항목 선택'}</span><ChevronDown size={18} />
          </button>}
          {!fulfillment && showChips && <div className="test-grid" id="test-chip-grid" aria-label="검사 종류">
            {eligibleTests.length ? eligibleTests.map(test => <button type="button" className="test-chip" key={test.id}
              aria-pressed={selected.includes(test.id)} onClick={() => choose(test.id)}>{test.name}</button>) :
              <Empty>소속 기관에 배정된 검사 항목이 없습니다.</Empty>}
          </div>}
          {!reservation && selectedTests.length > 0 && <div className="test-counts">{selectedTests.map(test =>
            <label key={test.id}>{test.name} <input type="number" min="1" max={Math.min(fulfillment?.remainingCount ?? 9999, 9999)} required aria-label={test.name + ' 검사 건수'}
              value={counts[test.id] ?? '1'} onChange={e => setCounts(prev => ({ ...prev, [test.id]: e.target.value }))} /> 건</label>)}</div>}
        </div>
        {!reservation && <fieldset className="field result-field"><legend>검사 결과 <b>*</b></legend>
          <div className="result-options">{(Object.keys(resultLabels) as ResultCode[]).map(code => <label key={code}>
            <input type="radio" name="result" value={code} checked={result === code} onChange={() => {
              setResult(code); setError(''); if (code !== 'FOLLOW_UP') setReferral(undefined)
            }} />{resultLabels[code]}
          </label>)}</div>
        </fieldset>}
      </fieldset>
      {canRefer && <div className="visit-referral-prompt">
        <div><strong>다른 보건소·보건지소 연계</strong>
          <p>{referralTests.length ? '연계 요청을 시작하면 검사 결과가 ‘조치 필요’로 선택됩니다.' : '현재 연계 요청을 받을 수 있는 기관이 등록되어 있지 않습니다.'}</p>
        </div>
        <button ref={referralButton} type="button" className="secondary" disabled={busy || !referralTests.length}
          aria-expanded={referralOpen} aria-controls="visit-referral-panel" onClick={() => {
            if (referralOpen) { referralItem.current?.focus(); return }
            setResult('FOLLOW_UP')
            setReferral(emptyReferral); setError('')
          }}><Link2 aria-hidden="true" size={16} />새 연계 요청</button>
      </div>}
      {!reservation && <div className="field memo-field"><label htmlFor="visit-memo">결과 상세 <b>*</b></label>
        <textarea id="visit-memo" rows={4} maxLength={2000} value={memo} onChange={e => setMemo(e.target.value)}
          disabled={busy} placeholder="시설 단위의 검사 결과를 입력하세요." required={!memoOnly} />
      </div>}
    </div>
    {referralOpen && referral && <section id="visit-referral-panel" className="visit-referral-panel" aria-labelledby="visit-referral-heading">
      <div><h2 id="visit-referral-heading"><Link2 aria-hidden="true" size={19} />보건소 연계</h2>
        <p>{referralTests.length ? '지원 항목과 받을 기관을 선택하세요. 요청을 보내면 해당 기관의 연계 요청함에 알림이 표시됩니다.'
          : '현재 연계 요청을 받을 수 있는 기관이 등록되어 있지 않습니다.'}</p>
      </div>
      <fieldset disabled={busy} className="visit-referral-fields">
        <div className="visit-referral-request">
          <div className="field"><label htmlFor="visit-referral-test">지원 항목 <b>*</b></label>
            <select id="visit-referral-test" ref={referralItem} value={referral.testTypeId ?? ''} required onChange={event => {
              setReferral({ ...referral, testTypeId: Number(event.target.value) || undefined, recipientOrganizationId: undefined }); setError('')
            }}>
              <option value="">지원 항목 선택</option>
              {referralTests.map(test => <option key={test.id} value={test.id}>{test.name}</option>)}
            </select>
          </div>
          <div className="field"><label htmlFor="visit-referral-count">요청 건수 <b>*</b></label>
            <input id="visit-referral-count" type="number" min="1" max="9999" required value={referral.count}
              onChange={event => setReferral({ ...referral, count: event.target.value })} />
          </div>
        </div>
        <fieldset className="field visit-referral-recipients"><legend>받을 기관 <b>*</b></legend>
          {recipients.length ? recipients.map(org => <label key={org.id}>
            <input type="radio" name="visit-referral-recipient" value={org.id} required
              checked={referral.recipientOrganizationId === org.id} onChange={() => {
                setReferral({ ...referral, recipientOrganizationId: org.id }); setError('')
              }} />{org.name}
          </label>) : <p>지원 항목을 선택하면 받을 수 있는 기관이 표시됩니다.</p>}
        </fieldset>
        <div className="field"><label htmlFor="visit-referral-note">공유 메모 <small>선택</small></label>
          <textarea id="visit-referral-note" rows={2} maxLength={500} placeholder="받을 기관에 전달할 내용을 입력하세요."
            value={referral.note} onChange={event => setReferral({ ...referral, note: event.target.value })} />
        </div>
      </fieldset>
      <button className="text-button" type="button" disabled={busy} onClick={() => {
        setReferral(undefined); setError(''); referralButton.current?.focus()
      }}>연계 취소</button>
    </section>}
    <hr />
    <Status error={error} />
    <footer className="form-footer">
      <p><Info size={17} />{referralOpen ? '방문 기록과 연계 요청을 저장하고 받을 기관에 앱 알림을 보냅니다.'
        : fulfillment ? '처리 기록을 저장하면 연계 요청의 남은 건수가 갱신됩니다.'
          : `저장한 ${reservation ? '예약' : '기록'}은 ${facility}의 방문 목록에 추가됩니다.`}</p>
      <div className="actions">
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>취소</button>
        <button type="submit" className={`primary ${reservation ? 'orange' : ''}`} disabled={busy || (referralOpen && !referralTests.length)}>
          {referralOpen ? <Bell size={18} aria-hidden="true" /> : <Check size={18} />}
          {busy ? '저장 중…' : reservation ? '예약 완료' : referralOpen ? '연계 요청 및 알림 보내기'
            : fulfillment ? '처리 기록 저장' : '기록 저장'}
        </button>
      </div>
    </footer>
    {reservation && <p className="sr-only"><Clock3 size={15} /> 한국 표준시 기준</p>}
  </form>
}
