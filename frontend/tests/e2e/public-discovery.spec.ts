import { expect, test } from '@playwright/test'

test('public snapshot supports exact, alias and unknown-query handling', async ({ page }) => {
  const apiRequests: string[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url())
  })
  await page.goto('/discovery', { waitUntil: 'networkidle' })
  await expect(page.getByRole('button', { name: '查询公开证据快照' })).toBeEnabled()

  const input = page.getByPlaceholder(/输入岗位或方向/)
  await input.fill('大模型推理加速工程师')
  await page.getByRole('button', { name: '查询公开证据快照' }).click()
  await expect(page.locator('.discovery-result')).toContainText('大模型推理加速工程师')
  await expect(page.locator('.result-evidence')).toContainText('4')

  await input.fill('LLM 推理优化')
  await page.getByRole('button', { name: '查询公开证据快照' }).click()
  await expect(page.locator('.discovery-result')).toContainText('大模型推理加速工程师')

  await input.fill('量子芯片验证工程师')
  await page.getByRole('button', { name: '查询公开证据快照' }).click()
  await expect(page.getByText('当前公开快照没有足够证据，请尝试下方已有证据方向。')).toBeVisible()
  await expect(page.locator('.discovery-result')).toHaveCount(0)
  expect(apiRequests).toEqual([])
})
