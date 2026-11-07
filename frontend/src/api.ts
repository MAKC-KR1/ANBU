import { useEffect, useState } from 'react'

export type Session = {
  accessToken: string
  expiresAt: number
  user: { id: number; name: string; role: string; organization: { id: number; name: string; type: string } }
}
export type Dong = { id: number; name: string; gu: string | null }
export type Place = { placeId: number; name: string; dongId: number; dongName: string; address: string; lat: number; lng: number }
type OrganizationRef = { id: number; name: string }
export type TestType = {
  id: number; name: string; serviceCode: string; responsibleOrganizations: OrganizationRef[]
  referralOrganizations?: OrganizationRef[]
}
export type ReferralDirection = 'incoming' | 'outgoing'
export type ReferralRequest = {
  requestId: number; sourceVisitId: number
  place: { id: number; name: string; dongId: number; dongName: string }
  testType: { id: number; name: string; serviceCode: string }
  requestedByOrganization: OrganizationRef; recipientOrganization: OrganizationRef | null
  plannedOrganization: OrganizationRef | null
  status: 'PENDING' | 'PLANNED' | 'DONE'
  requestedDate: string; plannedDate: string | null; completedDate: string | null
  requestedCount: number; completedCount: number; remainingCount: number; note?: string | null
  unread?: boolean
}
export type ResultCode = 'GOOD' | 'CAUTION' | 'FOLLOW_UP'
export type Visit = {
  visitId: number; placeId: number; visitDate: string; visitTime: string | null; resultCode: ResultCode | null; memo: string
  organization: { id: number; name: string }; createdAt: string
  performedTests: { testTypeId: number; name: string; count: number }[]
  createdRequestIds: number[]; fulfillments: unknown[]
}
export type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type Reservation = {
  id: number; placeId: number; visitDate: string; visitTime: string
  organizationName: string; testTypeId: number; testTypeName: string
}
export type Contact = { id: string; name: string; category: string; area: string; address: string; phone: string; hours: string; details: string; source: string }
export type Point = [number, number]
export type Geometry = { type: 'Polygon'; coordinates: Point[][] } | { type: 'MultiPolygon'; coordinates: Point[][][] }
export type Feature<P> = { type: 'Feature'; geometry: Geometry; properties: P }
export type Features<P> = { type: 'FeatureCollection'; features: Feature<P>[] }
export type PriorityPlace = { placeId: number; placeName: string; lastTestDate: string | null; elapsedMonths: number | null }
export type CityPriorities = { asOfDate: string; items: { rank: number; dongId: number; dongName: string; priorityPlace: PriorityPlace }[] }
export type PlacePriorities = { asOfDate: string; items: (PriorityPlace & { rank: number })[] }

const base = (import.meta.env.VITE_API_BASE || '/api/v1').replace(/\/$/, '')
const sessionKey = 'anbu.session'
export const isPreview = import.meta.env.DEV && import.meta.env.VITE_VOID_PREVIEW === 'true'
export const resultLabels: Record<ResultCode, string> = { GOOD: '양호', CAUTION: '주의', FOLLOW_UP: '조치 필요' }

export function restoreSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(sessionKey)
    if (!raw) return null
    const session = JSON.parse(raw) as Session
    return session.accessToken && session.user?.organization && session.expiresAt > Date.now() ? session : null
  } catch { return null }
}

export function storeSession(session: Session | null) {
  if (session) sessionStorage.setItem(sessionKey, JSON.stringify(session))
  else sessionStorage.removeItem(sessionKey)
}

export async function api<T>(path: string, token?: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(base + path, {
      ...options,
      headers: {
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...options.headers,
      },
    })
  } catch (e) {
    if (options.signal?.aborted) throw e
    throw new Error('서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')
  }
  if (response.status === 401 && token) window.dispatchEvent(new Event('anbu:expired'))
  const envelope = await response.json().catch(() => null)
  if (!response.ok || !envelope?.success) {
    throw new Error(envelope?.error?.message || '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  }
  return envelope.data as T
}

export function useRemote<T>(path: string | null, token?: string, revision = 0) {
  const [state, setState] = useState<{ key: string; data?: T; error?: string }>({ key: '' })
  const [retry, setRetry] = useState(0)
  const key = JSON.stringify([path, revision, retry])
  useEffect(() => {
    if (!path) return
    const controller = new AbortController()
    api<T>(path, token, { signal: controller.signal }).then(
      data => { if (!controller.signal.aborted) setState({ key, data }) },
      error => { if (!controller.signal.aborted) setState({ key, error: error.message }) },
    )
    return () => controller.abort()
  }, [path, token, key])
  return { ...(state.key === key ? state : {}), loading: Boolean(path && state.key !== key), retry: () => setRetry(n => n + 1) }
}

export async function readAllPages<T>(path: string, token: string, signal: AbortSignal): Promise<T[]> {
  const result: T[] = []
  for (let page = 0; ; page++) {
    const data = await api<Page<T>>(`${path}${path.includes('?') ? '&' : '?'}page=${page}&size=100`, token, { signal })
    result.push(...data.content)
    if (page + 1 >= data.totalPages) return result
  }
}

export const dateLabel = (date?: string | null) => date ? date.replaceAll('-', '.') : '—'
export const timeLabel = (time?: string | null) => time ? time.slice(0, 5) : ''
export const normalized = (str: string) => str.toLowerCase().replace(/\s/g, '')
const seoulFormatter = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Seoul' })
export const inSeoul = (date: Date) => seoulFormatter.format(date)
export const seoulDate = (dayOffset = 0) => inSeoul(new Date(Date.now() + dayOffset * 86_400_000))
