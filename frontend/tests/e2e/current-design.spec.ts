import { expect, test } from '@playwright/test'

const proof = (name: string) => `artifacts/current-review/${name}.png`

test('current desktop design keeps navigation and content compact', async ({ page }) => {
  const requests: string[] = []
  page.on('request', request => requests.push(request.url()))

  await page.goto('/abilities', { waitUntil: 'networkidle' })
  const mobile = page.viewportSize()!.width <= 860
  if (mobile) {
    await expect(page.locator('.public-mobile-header')).toBeVisible()
    await expect(page.locator('.mobile-task-nav a')).toHaveCount(7)
  } else {
    await expect(page.locator('.public-sidebar')).toBeVisible()
    await expect(page.locator('.public-task-nav a')).toHaveCount(7)
    await expect(page.getByRole('link', { name: '发现新岗位' })).toBeVisible()
  }
  await expect(page.locator('.career-atlas')).toBeVisible()
  expect(await page.locator('.atlas-role-branch').first().evaluate(element => getComputedStyle(element, '::before').content)).toBe('none')
  await expect(page.locator('.atlas-role-node > span').first()).toBeHidden()
  await expect(page.locator('.panorama-chart')).toHaveCount(0)
  expect(requests.some(url => /echarts|chart-engine/i.test(url))).toBe(false)
  await page.screenshot({ path: proof(`abilities-redesign-${page.viewportSize()!.width}`), fullPage: true })

  await page.goto('/discovery', { waitUntil: 'networkidle' })
  await expect(page.getByRole('heading', { name: '发现新岗位' })).toBeVisible()
  await expect(page.getByRole('button', { name: '查询公开证据快照' })).toBeEnabled()
  await expect(page.getByText('当前为只读快照；未收录方向会提示资料不足。')).toBeVisible()
  await page.screenshot({ path: proof(`discovery-public-snapshot-${page.viewportSize()!.width}`), fullPage: true })
})

test('diagnosis and plan let users reveal details', async ({ page }) => {
  await page.goto('/diagnosis?role=1', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /下一步：建立能力镜像/ }).click()
  await page.getByPlaceholder(/粘贴简历文字/).fill('姓名：陈同学\n本科 软件工程；掌握 Java、Spring Boot、MySQL；完成过后端项目。')
  await page.getByRole('button', { name: '识别能力事实' }).click()
  await page.getByRole('button', { name: '确认能力事实', exact: true }).click()
  await page.getByRole('button', { name: '运行双图对齐' }).click()
  await expect(page.locator('.diagnosis-alignment')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看详细信息' })).toBeVisible()
  await page.screenshot({ path: proof(`diagnosis-collapsed-${page.viewportSize()!.width}`), fullPage: true })

  await page.getByRole('button', { name: /生成 4–6 周行动计划/ }).click()
  await expect(page.locator('.plan-weeks article').first()).toBeVisible()
  await expect(page.locator('.plan-weeks article dl')).toHaveCount(0)
  await page.screenshot({ path: proof(`plan-collapsed-${page.viewportSize()!.width}`), fullPage: true })
})
