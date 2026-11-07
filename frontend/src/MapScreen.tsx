import { useId, useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react'
import { MapPin } from 'lucide-react'
import { dateLabel, isPreview, useRemote, type CityPriorities, type Dong, type Features, type Geometry, type Place,
  type PlacePriorities, type Point, type TestType } from './api'
import { cityMapArtwork } from './cityMapArtwork'
import { daebunamHeroPoint, daebunamMapArtwork, daebunamMapOutline, daebunamMapViewBox } from './daebunamMapArtwork'
import legal from './daebu-legal.json'
import { Empty, Pill, Status } from './ui'

const daebuLegal = legal as Features<{ name: string; code: string }>
const daebunam = '대부남동'
const target = '흥성마을경로당'
const daebunamArtworkByName = new Map(daebunamMapArtwork.map(area => [area.name, area]))
const levelColors = { 1: '#870000', 2: '#CE2C2C', 3: '#FCA9A9', 4: '#C6CEF7' }
const levels = [1, 2, 3, 4] as const
const cityBands = [2, 3, 4] as const
type Level = typeof levels[number]

const facilityLevel = (rank: number): Level => rank === 1 ? 1 : rank <= 4 ? 2 : rank <= 8 ? 3 : 4
const color = (level: Level | undefined, urgent: boolean) => level === undefined || (urgent && level === 4)
  ? '#dfe5f2' : levelColors[level]

function cityLevels(items: CityPriorities['items']) {
  const byDate = new Map<string | null, CityPriorities['items']>()
  for (const item of items) {
    const date = item.priorityPlace.lastTestDate
    const tied = byDate.get(date) ?? []
    tied.push(item)
    byDate.set(date, tied)
  }
  const byDong = new Map<number, Level>()
  let position = 0
  for (const tied of byDate.values()) {
    // Keep the API's date order. Within a tie, alternate any bands it crosses.
    const bandSizes = [0, 0, 0]
    for (const _ of tied) bandSizes[Math.floor(3 * position++ / items.length)]++
    let index = 0
    for (let row = 0; row < Math.max(...bandSizes); row++) {
      for (const band of cityBands) {
        if (row < bandSizes[band - 2]) byDong.set(tied[index++].dongId, band)
      }
    }
  }
  return byDong
}

function rings(geometry: Geometry): Point[][] {
  return geometry.type === 'Polygon' ? geometry.coordinates : geometry.coordinates.flat()
}
const detailMapWidth = 728.77
const detailMapHeight = 512.52
const detailMapFrame = [0, 0, detailMapWidth, detailMapHeight]

function projection(geometries: Geometry[], frame: number[]) {
  let left = Infinity, right = -Infinity, bottom = Infinity, top = -Infinity
  for (const geometry of geometries) for (const ring of rings(geometry)) for (const [x, y] of ring) {
    left = Math.min(left, x); right = Math.max(right, x); top = Math.max(top, y); bottom = Math.min(bottom, y)
  }
  if (!Number.isFinite(left)) return (_point: Point): Point => [0, 0]
  const ratio = Math.cos((top + bottom) * Math.PI / 360)
  const width = Math.max((right - left) * ratio, 1e-6)
  const height = Math.max(top - bottom, 1e-6)
  const [fx, fy, fw, fh] = frame
  const scale = Math.min(fw / width, fh / height)
  const x0 = fx + (fw - width * scale) / 2, y0 = fy + (fh - height * scale) / 2
  return ([x, y]: Point): Point => [x0 + (x - left) * ratio * scale, y0 + (top - y) * scale]
}
const svgPath = (geometry: Geometry, project: (p: Point) => Point) => rings(geometry).map(ring =>
  'M' + ring.map(p => project(p).map(n => n.toFixed(1)).join(',')).join('L') + 'Z').join(' ')
const legalName = (address: string) => daebuLegal.features.find(f => address.includes('(' + f.properties.name + ')'))?.properties.name

type Props = {
  token: string; dongId?: number; dongs: Dong[]; places: Place[]; tests: TestType[]; revision: number
  onDong: (id?: number) => void; onPlace: (place: Place) => void
}
type Hover = { name: string; detail?: string; level?: Level; date?: string | null; x: number; y: number }
export function MapScreen({ token, dongId, dongs, places, tests, revision, onDong, onPlace }: Props) {
  const [filter, setFilter] = useState<number | 'urgent' | undefined>()
  const [scope, setScope] = useState(daebunam)
  const [hover, setHover] = useState<Hover | null>(null)
  const clipId = useId().replaceAll(':', '')
  const dong = dongs.find(item => item.id === dongId)
  const isDaebu = dong?.name === '대부동'
  const isDaebunam = isDaebu && scope === daebunam
  const byDong = new Map(dongs.map(item => [item.name, item]))
  const byPlace = new Map(places.map(item => [item.placeId, item]))
  const param = typeof filter === 'number' ? '?testTypeId=' + filter : ''
  const boundaries = useRemote<Features<{ name: string; gu: string }>>(dongId ? '/map/boundaries' : null, token)
  const cityRanks = useRemote<CityPriorities>(dongId ? null : '/dongs/priorities' + param, token, revision)
  const districtRanks = useRemote<PlacePriorities>(dongId ? '/dongs/' + dongId + '/places/priorities' + param : null, token, revision)
  const coverage = useRemote<Features<{ placeId: number; name: string }>>(dongId ? '/map/dongs/' + dongId + '/coverage' : null, token)
  const useDaebunamArtwork = isDaebunam && coverage.data?.features.every(({ properties }) => {
    const place = byPlace.get(properties.placeId)
    return !place || legalName(place.address) !== daebunam || daebunamArtworkByName.has(properties.name)
  })
  const cityItems = cityRanks.data?.items
  const rankByDong = new Map(cityItems?.map(item => [item.dongId, item]))
  const levelByDong = useMemo(() => cityLevels(cityItems ?? []), [cityItems])
  const rankByPlace = new Map(districtRanks.data?.items.map(item => [item.placeId, item]))
  const dongBoundary = boundaries.data?.features.find(feature => feature.properties.name === dong?.name)
  const section = isDaebu && scope !== '전체' && scope !== '본섬' ? daebuLegal.features.find(f => f.properties.name === scope) : undefined
  const land = useMemo(() => daebuLegal.features.filter(f => f.properties.name !== '풍도동').map(f => f.geometry), [])
  const project = useMemo(() => projection(section ? [section.geometry] :
    isDaebu && scope === '본섬' ? land : dongBoundary ? [dongBoundary.geometry] : [], detailMapFrame),
  [section, isDaebu, scope, land, dongBoundary])
  const hero = isDaebu && (scope === '본섬' || scope === '전체' || isDaebunam)
    ? places.find(place => place.dongId === dongId && place.name === target) : undefined
  const heroRank = hero && rankByPlace.get(hero.placeId)
  const rawHeroPoint = hero && (useDaebunamArtwork ? daebunamHeroPoint : project([hero.lng, hero.lat]))
  const heroPoint = rawHeroPoint && rawHeroPoint[0] >= 0 && rawHeroPoint[0] <= detailMapWidth
    && rawHeroPoint[1] >= 0 && rawHeroPoint[1] <= detailMapHeight ? rawHeroPoint : undefined

  function show(event: MouseEvent<SVGElement>, name: string, detail?: string, level?: Level, date?: string | null) {
    const box = event.currentTarget.closest('.map-figure')?.getBoundingClientRect()
    if (box) setHover({ name, detail, level, date, x: Math.max(7, Math.min(event.clientX - box.left, box.width - 180)),
      y: Math.max(8, event.clientY - box.top - 75) })
  }
  function focus(event: React.FocusEvent<SVGElement>, name: string, detail?: string, level?: Level, date?: string | null) {
    const box = event.currentTarget.closest('.map-figure')?.getBoundingClientRect()
    const item = event.currentTarget.getBoundingClientRect()
    if (box) setHover({ name, detail, level, date, x: Math.max(8, Math.min(item.left + item.width / 2 - box.left, box.width - 180)),
      y: Math.max(8, item.top - box.top - 48) })
  }
  function key(event: KeyboardEvent<SVGElement>, action: () => void) {
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setHover(null); action() }
  }
  function actionProps(name: string, action: () => void, detail?: string, level?: Level, date?: string | null) {
    return {
      role: 'button', tabIndex: 0, 'aria-label': name,
      onClick: () => { setHover(null); action() },
      onKeyDown: (e: KeyboardEvent<SVGElement>) => key(e, action),
      onMouseEnter: (e: MouseEvent<SVGElement>) => show(e, name, detail, level, date),
      onMouseMove: (e: MouseEvent<SVGElement>) => show(e, name, detail, level, date),
      onFocus: (e: React.FocusEvent<SVGElement>) => focus(e, name, detail, level, date),
      onBlur: () => setHover(null), onMouseLeave: () => setHover(null),
    }
  }
  const urgent = filter === 'urgent'
  const useReferenceRank = isPreview && filter === undefined
  const loading = boundaries.loading || (dongId ? coverage.loading || districtRanks.loading : cityRanks.loading)
  const error = boundaries.error || (dongId ? coverage.error || districtRanks.error : cityRanks.error)
  return <section className={`map-section ${dongId ? 'map-section-detail' : 'map-section-city'}`}
    aria-label={dongId ? '동 상세 우선순위 지도' : '안산 전체 우선지역 지도'}>
    <div className="map-toolbar">
      <div className="map-filters">
        <Pill selected={filter === undefined} onClick={() => setFilter(undefined)}>전체</Pill>
        <Pill selected={urgent} onClick={() => setFilter('urgent')}>우선 검사 필요</Pill>
        {tests.map(test => <Pill key={test.id} selected={filter === test.id} onClick={() => setFilter(test.id)}>{test.name}</Pill>)}
      </div>
      {isDaebu && <label className="scope-filter">상세 구역
        <select value={scope} onChange={e => { setHover(null); setScope(e.target.value) }}>
          <option value="본섬">대부도 본섬·선감동</option><option value="전체">대부동 전체</option>
          {daebuLegal.features.map(f => <option key={f.properties.code}>{f.properties.name}</option>)}
        </select>
      </label>}
    </div>
    <Status loading={loading} error={error} retry={() => { boundaries.retry(); coverage.retry(); cityRanks.retry(); districtRanks.retry() }} />
    {!error && (dongId ? dongBoundary : dongs.length > 0) && <div className="map-figure" onMouseLeave={() => setHover(null)}>
      <div className={`legend${isDaebunam ? ' daebunam-legend' : ''}`} aria-label="우선순위 범례">
        <strong>{isDaebunam ? '대부남동 우선 순위도' : dongId ? '검사 우선순위' : '우선 순위도'}</strong>
        {levels.map(level => <span key={level}><i style={{ background: levelColors[level] }} />{level}</span>)}
      </div>
      <svg viewBox={useDaebunamArtwork ? daebunamMapViewBox : dongId ? `0 0 ${detailMapWidth} ${detailMapHeight}` : '118 0 1102 817'} className="priority-map"
        aria-label={dongId ? '시설별 방문 구역' : '안산시 행정동'} data-level={dongId ? 'detail' : 'city'}>
        {section && <defs><clipPath id={clipId}>
          <path d={useDaebunamArtwork ? daebunamMapOutline : svgPath(section.geometry, project)} clipRule="evenodd" />
        </clipPath></defs>}
        {!dongId && cityMapArtwork.map(({ name, d, rank, group, dongName }) => {
          const isLegal = group === 'legal'
          const item = byDong.get(dongName ?? (isLegal ? '대부동' : name))
          const level = name === daebunam ? 1 : useReferenceRank ? rank : item && levelByDong.get(item.id)
          const date = !useReferenceRank && item ? rankByDong.get(item.id)?.priorityPlace.lastTestDate : undefined
          const nextScope = name === '풍도' || name === '육도' ? '풍도동' : name === '대부동동' || name === daebunam ? daebunam : '본섬'
          return <path className={isLegal ? 'area legal-area' : 'area'} key={name} fill={color(level, urgent)} d={d} fillRule="evenodd"
            {...(item ? actionProps(name, () => { setScope(nextScope); onDong(item.id) }, item.gu ?? undefined, level, date) : {})} />
        })}
        {!dongId && <g className="map-caption">
          <text x="1046" y="531">(행정동 위치도)</text>
          <text x="469" y="730">(법정동 위치도)</text>
        </g>}
        {dongId && dongBoundary && !useDaebunamArtwork && <path className="dong-base" fill="#e1e7f7" fillRule="evenodd" d={svgPath(dongBoundary.geometry, project)} />}
        {dongId && <g className={useDaebunamArtwork ? 'daebunam-artwork' : undefined} clipPath={section ? `url(#${clipId})` : undefined}>
          {coverage.data?.features.map(feature => {
            const { placeId, name } = feature.properties
            const place = byPlace.get(placeId)
            const rank = rankByPlace.get(placeId)
            const legalDong = place && legalName(place.address)
            if (section && legalDong !== scope) return null
            if (isDaebu && scope === '본섬' && legalDong === '풍도동') return null
            const artwork = useDaebunamArtwork ? daebunamArtworkByName.get(name) : undefined
            const level = name === target ? 1 : useReferenceRank && artwork ? artwork.rank : rank && facilityLevel(rank.rank)
            return <path className="area" key={placeId} fill={color(level, urgent)} fillRule="evenodd" d={artwork?.d ?? svgPath(feature.geometry, project)}
              {...(place ? actionProps(name, () => onPlace(place), legalDong || dong?.name, level, rank?.lastTestDate) : {})} />
          })}
        </g>}
        {hero && heroPoint && <g className="map-pin" transform={`translate(${heroPoint[0]} ${heroPoint[1]})`}
          {...actionProps('흥성마을경로당 위치', () => onPlace(hero), daebunam, 1, heroRank?.lastTestDate)}>
          <circle r="7" /><path d="M4 5L15 14" /><rect x="14" y="7" width="132" height="28" rx="10" />
          <text x="25" y="26">흥성마을경로당</text>
        </g>}
      </svg>
      {hover && <div role="tooltip" className="map-tooltip" style={{ left: hover.x, top: hover.y }}>
        <strong>{hover.name}</strong>{hover.detail && <small>{hover.detail}</small>}
        {hover.level && <small>{hover.level}순위{hover.date !== undefined && (
          hover.date ? ' · 최근 검사 ' + dateLabel(hover.date) : ' · 검사 이력 없음'
        )}</small>}
      </div>}
    </div>}
    {!loading && !error && dongId && !coverage.data?.features.length && <Empty>이 동의 지도에 표시할 시설 담당 구역이 없습니다.</Empty>}
    {!loading && !error && !dongId && !dongs.length && <Empty>등록된 행정동이 없습니다.</Empty>}
    {hero && !isDaebunam && <button type="button" onClick={() => onPlace(hero)} className="text-button map-facility-link">
      <MapPin size={14} />흥성마을경로당
    </button>}
  </section>
}
