import { expect, test } from '@playwright/test'

const routes = [
  '/', '/showcase', '/jobs', '/discovery', '/evolution', '/abilities', '/diagnosis', '/plan'
]

for (const route of routes) {
  test(`${route} renders without runtime errors`, async ({ page }, testInfo) => {
    const runtimeErrors: string[] = []
    const serverErrors: string[] = []
    page.on('console', message => {
      if (message.type() === 'error') runtimeErrors.push(message.text())
    })
    page.on('pageerror', error => runtimeErrors.push(error.message))
    page.on('response', response => {
      if (response.status() >= 500) serverErrors.push(`${response.status()} ${response.url()}`)
    })

    await page.goto(route, { waitUntil: 'networkidle' })
    await expect(page.locator('.public-shell')).toBeVisible()
    const text = await page.locator('body').innerText()
    expect(text).not.toMatch(/undefined|NaN/)
    expect(runtimeErrors).toEqual([])
    expect(serverErrors).toEqual([])

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
    expect(overflow).toBeLessThanOrEqual(1)

    if (['/', '/abilities', '/discovery', '/evolution'].includes(route)) {
      await page.screenshot({
        path: `test-results/screenshots/${testInfo.project.name}-${route.replaceAll('/', '-') || 'home'}.png`,
        fullPage: true
      })
    }
  })
}

test('legacy paths keep their redirects', async ({ page }) => {
  for (const [legacyPath, currentPath] of Object.entries({
    '/career': '/diagnosis',
    '/graph': '/abilities',
    '/growth': '/plan',
    '/demo': '/showcase',
    '/evaluation': '/workspace',
    '/market': '/jobs',
    '/data': '/workspace',
    '/governance': '/workspace',
    '/professional': '/professional/verifications',
    '/professional/login': '/professional/verifications',
    '/professional/panorama': '/abilities',
    '/professional/emerging': '/discovery',
    '/professional/evolution': '/evolution',
    '/professional/roles': '/jobs',
    '/professional/real-data': '/discovery',
    '/professional/market': '/jobs',
    '/professional/data': '/workspace',
    '/professional/governance': '/professional/verifications',
    '/professional/evaluation': '/workspace'
  })) {
    await page.goto(legacyPath, { waitUntil: 'networkidle' })
    await expect.poll(() => new URL(page.url()).pathname).toBe(currentPath)
    await expect(page.locator('.public-shell')).toBeVisible()
  }
  await page.goto('/this-page-does-not-exist', { waitUntil: 'networkidle' })
  await expect(page.getByRole('heading', { name: '没有找到这个页面' })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回职业首页' })).toBeVisible()
})

test('public evidence snapshot never sends API requests', async ({ page }) => {
  const apiRequests: string[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url())
  })
  for (const route of [...routes, '/professional/verifications']) {
    await page.goto(route, { waitUntil: 'networkidle' })
  }
  expect(apiRequests).toEqual([])
})
