import { expect, test } from '@playwright/test'

test('discovery is the single public entry and snapshots only query verified public evidence', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('console', message => {
    if (message.type() === 'error') runtimeErrors.push(message.text())
  })
  page.on('pageerror', error => runtimeErrors.push(error.message))

  await page.goto('/discovery', { waitUntil: 'networkidle' })
  await expect(page.locator('.public-shell')).toBeVisible()
  await expect(page.getByRole('heading', { name: '发现新岗位' })).toBeVisible()
  await expect(page.getByText(/当前使用公开证据快照/)).toBeVisible()
  await expect(page.getByRole('button', { name: '查询公开证据快照' })).toBeEnabled()
  await expect(page.getByText(/未收录方向不会生成推测性结论/)).toBeVisible()
  expect(runtimeErrors).toEqual([])
})

test('new emerging role is visible in the job direction filter', async ({ page }) => {
  await page.goto('/jobs', { waitUntil: 'networkidle' })
  await page.locator('.filter-dock .el-select').click()
  await page.getByRole('option', { name: '新兴岗位' }).click()
  const emergingRole = page.locator('.job-compact-card.emerging').filter({ hasText: '大模型推理加速工程师' })
  await expect(emergingRole).toBeVisible()
  await expect(emergingRole.locator('.role-status')).toHaveText('新兴岗位')
})
