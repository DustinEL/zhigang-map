import { expect, test, type Locator, type Page } from '@playwright/test'

async function expectNavigation(page: Page, entry: Locator, path: RegExp) {
  await entry.scrollIntoViewIfNeeded()
  await entry.click()
  await expect(page).toHaveURL(path)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
  await expect(page.locator('main h1').first()).toBeInViewport()
}

test('highlighted diagnosis and plan entries navigate visibly', async ({ page }) => {
  await page.goto('/workspace')
  await expectNavigation(page, page.locator('.workspace-primary-action'), /\/diagnosis$/)

  await page.goto('/workspace')
  await expectNavigation(page, page.locator('.workspace-shortcuts a[href="/diagnosis"]'), /\/diagnosis$/)

  await page.goto('/workspace')
  await expectNavigation(page, page.locator('.workspace-shortcuts a[href="/plan"]'), /\/plan$/)

  await page.goto('/')
  await expectNavigation(page, page.getByRole('button', { name: /开始职业诊断/ }), /\/diagnosis$/)

  await page.goto('/')
  await expectNavigation(page, page.getByRole('button', { name: '开始对齐' }).first(), /\/diagnosis\?role=\d+$/)

  await page.goto('/jobs')
  await expectNavigation(page, page.getByRole('button', { name: '开始诊断' }).first(), /\/diagnosis\?role=\d+$/)

  await page.goto('/abilities')
  await expectNavigation(page, page.getByRole('button', { name: '开始能力分析' }), /\/diagnosis\?role=\d+$/)

  await page.goto('/abilities')
  await page.getByRole('button', { name: '查看完整画像' }).click()
  await expectNavigation(page, page.getByRole('button', { name: '建立我的能力镜像' }), /\/diagnosis\?role=\d+$/)
})

test('sidebar actions expose discovery as the only data-update entry', async ({ page }) => {
  test.skip(page.viewportSize()!.width <= 860, 'Desktop sidebar is hidden on mobile')

  await page.goto('/abilities')
  const sidebar = page.locator('.public-sidebar')
  const discovery = sidebar.getByRole('link', { name: '发现新岗位' })
  await expect(sidebar.getByText('岗位智能端')).toHaveCount(0)
  await expect(discovery).not.toHaveClass(/active/)
  await expectNavigation(page, sidebar.getByRole('link', { name: '职业诊断' }), /\/diagnosis$/)

  await page.goto('/abilities')
  await expectNavigation(page, sidebar.getByRole('link', { name: '行动计划' }), /\/plan$/)

  await page.goto('/discovery')
  await expect(discovery).toHaveClass(/active/)
  await expect(page.getByRole('heading', { name: '发现新岗位' })).toBeVisible()
})
