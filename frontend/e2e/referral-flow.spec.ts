import { expect, test, type Page } from '@playwright/test'

const sender = '대부남동보건진료소'
const recipient = '대부보건지소'
const place = '남동경로당'
const supportItem = '근육·관절'

async function login(page: Page, organization: string) {
  await page.goto('/')
  await page.getByLabel('아이디').fill(organization)
  await page.getByLabel('비밀번호', { exact: true }).fill('1111')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page.locator('.sidebar').getByText(organization, { exact: true })).toBeVisible()
}

test('a visit referral notifies the selected institution and tracks its progress in both accounts', async ({ page, browser, baseURL }) => {
  const shareNote = '시설 담당자와 검사 지원 일정을 조율해 주세요.'
  const senderMemo = '방문 기록 내부 메모입니다.'

  await login(page, sender)
  await page.getByRole('button', { name: '대부동동', exact: true }).press('Enter')
  const facility = page.getByRole('region', { name: '동 상세 우선순위 지도' })
    .getByRole('button', { name: place, exact: true })
  await expect(facility).toBeVisible()
  await expect(page.getByRole('button', { name: '새 연계 요청', exact: true })).toHaveCount(0)
  await facility.press('Enter')

  const referralDialog = page.getByRole('dialog')
  await expect(referralDialog.getByRole('heading', { name: place, exact: true })).toBeVisible()
  await expect(referralDialog.getByRole('button', { name: '새 연계 요청', exact: true })).toHaveCount(0)
  await referralDialog.getByRole('button', { name: '새 기록 작성', exact: true }).click()
  await referralDialog.getByRole('button', { name: '새 연계 요청', exact: true }).click()
  await expect(referralDialog.getByRole('radio', { name: '조치 필요', exact: true })).toBeChecked()
  const referralPanel = referralDialog.getByRole('region', { name: '보건소 연계' })
  const supportOptions = referralPanel.getByRole('combobox', { name: /지원 항목/ })
  await expect(supportOptions.getByRole('option')).toHaveText([
    '지원 항목 선택', '기초 건강·만성질환', '대사·체성분', '인지·치매', '구강·치과',
    '결핵', '눈·귀', '심장·수면', '근육·관절',
  ])
  await supportOptions.selectOption({ label: '인지·치매' })
  await expect(referralPanel.getByRole('radio')).toHaveCount(2)
  await expect(referralPanel.getByRole('radio', { name: '상록수보건소', exact: true })).toBeVisible()
  await referralPanel.getByRole('radio', { name: '단원보건소', exact: true }).check()
  await supportOptions.selectOption({ label: supportItem })
  await expect(referralPanel.getByRole('radio')).toHaveCount(1)
  const recipientChoice = referralPanel.getByRole('radio', { name: recipient, exact: true })
  await expect(recipientChoice).not.toBeChecked()
  await expect(referralDialog.getByRole('spinbutton', { name: /요청 건수/ })).toHaveValue('1')
  await recipientChoice.check()
  await referralDialog.getByLabel(/공유 메모/).fill(shareNote)
  await referralDialog.getByLabel(/결과 상세/).fill(senderMemo)
  await referralDialog.getByRole('button', { name: '연계 요청 및 알림 보내기', exact: true }).click()
  await page.getByRole('dialog').getByRole('link', { name: '보낸 요청 보기', exact: true }).click()

  const outgoing = page.getByRole('region', { name: '보낸 요청 목록' })
    .getByRole('button', { name: new RegExp(`${supportItem}.*${place}.*${recipient}`) })
  await expect(outgoing).toContainText('요청 대기')
  await expect(outgoing).not.toContainText('새 알림')
  await expect(page.getByRole('button', { name: '새 연계 요청', exact: true })).toHaveCount(0)

  const recipientContext = await browser.newContext({ baseURL })
  try {
    const recipientPage = await recipientContext.newPage()
    await login(recipientPage, recipient)
    const recipientSidebar = recipientPage.locator('.sidebar')
    const unreadLink = recipientSidebar.getByRole('link', { name: '연계 요청, 새 알림 1건', exact: true })
    await unreadLink.click()
    const incoming = recipientPage.getByRole('region', { name: '받은 요청 목록' })
      .getByRole('button', { name: new RegExp(`${supportItem}.*${place}.*${sender}`) })
    await expect(incoming).toContainText('요청 대기')
    await expect(incoming).toContainText('새 알림')
    await recipientPage.reload()
    await expect(unreadLink).toBeVisible()
    await expect(incoming).toContainText('새 알림')
    await incoming.click()

    const detail = recipientPage.getByRole('complementary', { name: '연계 요청 상세' })
    await expect(detail.getByText(shareNote, { exact: true })).toBeVisible()
    await expect(recipientPage.getByText(senderMemo, { exact: true })).toHaveCount(0)
    await detail.getByRole('button', { name: '알림 확인', exact: true }).click()
    const readLink = recipientSidebar.getByRole('link', { name: '연계 요청', exact: true })
    await expect(readLink).toBeVisible()
    await expect(incoming).not.toContainText('새 알림')
    await recipientPage.reload()
    await expect(incoming).not.toContainText('새 알림')
    await expect(readLink).toBeVisible()
    await detail.getByRole('button', { name: '방문 일정 저장', exact: true }).click()
    await expect(incoming).toContainText('방문 예정')
    await page.reload()
    await expect(outgoing).toContainText('방문 예정')

    await detail.getByRole('button', { name: '처리 기록 작성', exact: true }).click()
    const fulfillment = recipientPage.getByRole('dialog', { name: '연계 요청 처리' })
    await fulfillment.getByLabel(/결과 상세/).fill('지원 검사 1건을 완료했습니다.')
    await fulfillment.getByRole('button', { name: '처리 기록 저장', exact: true }).click()
    await expect(fulfillment).toBeHidden()
    await expect(incoming).toContainText('완료')
    await expect(detail.getByText('1 / 1건', { exact: true })).toBeVisible()

    await page.reload()
    await expect(outgoing).toContainText('완료')
  } finally {
    await recipientContext.close()
  }
})
