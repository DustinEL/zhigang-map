import { expect, test } from '@playwright/test'

test('static role evolution shows controlled windows and evidence without write actions', async ({ page }) => {
  await page.goto('/evolution?role=2', { waitUntil: 'networkidle' })

  await expect(page.getByRole('heading', { name: '岗位演化' })).toBeVisible()
  await expect(page.getByText('当前为只读静态快照')).toBeVisible()
  await expect(page.getByText('基线窗口')).toBeVisible()
  await expect(page.getByText('当前窗口')).toBeVisible()
  await expect(page.getByText('Wilson 95% + Newcombe')).toBeVisible()
  await expect(page.getByRole('button', { name: /查看 \d+ 条原文证据/ }).first()).toBeVisible()
  await page.getByRole('button', { name: /查看 \d+ 条原文证据/ }).first().click()
  await expect(page.getByRole('dialog')).toContainText(/基线|当前/)
  await expect(page.getByRole('button', { name: '保存草稿' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '确认并发布图谱版本' })).toHaveCount(0)
})
