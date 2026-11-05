import { expect, test } from '@playwright/test'

test('home loads its core message without chart or professional requests', async ({ page }) => {
  const requests: string[] = []
  page.on('request', request => requests.push(request.url()))
  const startedAt = Date.now()
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await expect(page.getByRole('heading', { name: /找到你的.*职业坐标/ })).toBeVisible({ timeout: 2_500 })
  expect(Date.now() - startedAt).toBeLessThan(2_500)
  await page.waitForLoadState('networkidle')
  expect(requests.some(url => /echarts|graphcanvas|graph-canvas/i.test(url))).toBe(false)
  expect(requests.some(url => /\/api\/(runtime-status|review-queues|candidate-skills|professional)/.test(url))).toBe(false)
})

test('public shell exposes the student task navigation', async ({ page }) => {
  await page.goto('/', { waitUntil: 'networkidle' })
  const navigation = page.viewportSize()!.width <= 860 ? page.locator('.mobile-task-nav') : page.locator('.public-task-nav')
  await expect(navigation.getByRole('link', { name: '岗位方向' })).toBeVisible()
  await expect(navigation.getByRole('link', { name: '岗位图谱' })).toBeVisible()
  await expect(navigation.getByRole('link', { name: '职业诊断' })).toBeVisible()
  await expect(navigation.getByRole('link', { name: '行动计划' })).toBeVisible()
  await expect(page.locator('.public-shell').getByText(/专业快照|审核批次|冻结缓存|数据治理/)).toHaveCount(0)
  await page.goto('/jobs', { waitUntil: 'networkidle' })
  const brand = page.viewportSize()!.width <= 860 ? page.locator('.public-mobile-header .public-brand') : page.locator('.public-sidebar .public-brand')
  await brand.click()
  await expect(page).toHaveURL(/\/$/)
})

test('baseline roles do not expose fake market numbers', async ({ page }) => {
  await page.goto('/jobs', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /查看更多岗位/ }).click()
  const baseline = page.locator('article.job-compact-card.baseline').first()
  await expect(baseline).toBeVisible()
  await expect(baseline).not.toContainText(/\d+ 条样本|\d+ 家企业/)
})

test('home exposes the live role catalog in three trustworthy layers', async ({ page }) => {
  await page.goto('/', { waitUntil: 'networkidle' })
  await expect(page.locator('.hero-role-button')).toHaveCount(10)
  await expect(page.locator('.hero-role-button.published')).toHaveCount(8)
  await expect(page.locator('.hero-role-button.baseline')).toHaveCount(2)
  await expect(page.locator('.hero-role-button.building')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /大模型推理加速工程师/ })).toBeVisible()
  await expect(page.locator('.hero-role-catalog')).not.toContainText(/已满足|证据不足|关键缺口/)
})

test('abilities role drawer opens from graph nodes and preserves published and baseline semantics', async ({ page }) => {
  await page.goto('/abilities', { waitUntil: 'networkidle' })
  await expect(page.getByRole('tab', { name: '全景岗位图谱' })).toHaveAttribute('aria-selected', 'true')

  for (const status of ['published', 'baseline']) {
    const roleNode = page.locator(`.atlas-role-branch.${status} .atlas-role-node`).first()
    await roleNode.click()
    const drawer = page.locator('.role-detail-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.locator('.panorama-role-detail')).toBeVisible()

    if (status === 'published') {
      await expect(drawer).toContainText(/条真实样本/)
      await expect(drawer).toContainText(/家企业/)
    } else if (status === 'baseline') {
      await expect(drawer).toContainText('当前为能力基线，可用于了解岗位方向和进行能力自测。')
      await expect(drawer).not.toContainText(/\d+ 条真实样本|\d+ 家企业/)
    }

    await page.keyboard.press('Escape')
    await expect(drawer).toBeHidden()
  }
})

test('abilities drawer close and complete-profile actions reveal the selected alignment tab', async ({ page }) => {
  await page.goto('/abilities', { waitUntil: 'networkidle' })
  const roleNode = page.locator('.atlas-role-branch.published .atlas-role-node').first()
  await roleNode.click()
  const drawer = page.locator('.role-detail-drawer')
  await expect(drawer).toBeVisible()

  await drawer.locator('.el-drawer__close-btn').click()
  await expect(drawer).toBeHidden()

  await roleNode.click()
  await drawer.getByRole('button', { name: '查看完整画像' }).click()
  await expect(drawer).toBeHidden()
  await expect(page.getByRole('tab', { name: '岗位画像与对齐' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.alignment-list article').first()).toBeVisible()
})

test('abilities role drawer stays within desktop and mobile viewports', async ({ page }) => {
  await page.goto('/abilities', { waitUntil: 'networkidle' })
  await page.locator('.atlas-role-branch.published .atlas-role-node').first().click()
  const drawer = page.locator('.role-detail-drawer')
  await expect(drawer).toBeVisible()
  const bounds = await drawer.boundingBox()
  const viewport = page.viewportSize()!
  expect(bounds).not.toBeNull()
  if (viewport.width <= 860) {
    expect(bounds!.width).toBeGreaterThanOrEqual(viewport.width - 1)
    expect(bounds!.width).toBeLessThanOrEqual(viewport.width + 1)
  } else {
    expect(bounds!.width).toBeGreaterThanOrEqual(680)
    expect(bounds!.width).toBeLessThanOrEqual(710)
  }
  expect(bounds!.x).toBeGreaterThanOrEqual(0)
  expect(await page.locator('.role-detail-drawer .panorama-detail-scroll').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('panorama filters change visible roles and relations', async ({ page }) => {
  await page.goto('/abilities', { waitUntil: 'networkidle' })
  await expect(page.locator('.panorama-stats')).toContainText('岗位 10')

  await page.getByRole('combobox', { name: '筛选技术栈' }).click()
  await page.getByRole('option', { name: 'Java', exact: true }).click()

  await expect(page.locator('.panorama-stats')).toContainText('岗位 4')
  await expect(page.locator('.panorama-role-detail')).toContainText('Java 开发工程师')
  await expect(page.locator('.career-atlas')).not.toContainText('JavaScript')
})

test('student completes four-step diagnosis and evidence verification', async ({ page }) => {
  const runtimeErrors: string[] = []
  page.on('console', message => { if (message.type() === 'error') runtimeErrors.push(message.text()) })
  page.on('pageerror', error => runtimeErrors.push(error.message))

  await page.goto('/diagnosis?role=1', { waitUntil: 'networkidle' })
  await expect(page.locator('.diagnosis-steps button')).toHaveCount(4)
  await page.getByRole('button', { name: /下一步：建立能力镜像/ }).click()
  await expect(page.getByRole('tab', { name: '上传简历' })).toHaveCount(0)
  await page.getByPlaceholder(/粘贴简历文字/).fill('姓名：陈同学\n本科 软件工程；掌握 Java、Spring Boot、MySQL；完成过后端项目。')
  await page.getByRole('button', { name: '识别能力事实' }).click()
  await expect(page.locator('.facts-stage')).toBeVisible()
  await page.getByRole('button', { name: '确认能力事实', exact: true }).click()
  await page.getByRole('button', { name: '运行双图对齐' }).click()
  await expect(page.locator('.diagnosis-result-hero')).toBeVisible()
  await expect(page.getByText('用于衡量岗位能力覆盖，不表示录用概率')).toBeVisible()
  await page.getByRole('button', { name: /生成 4–6 周行动计划/ }).click()
  await expect(page).toHaveURL(/\/plan/)
  await expect(page.locator('.plan-weeks article').first()).toBeVisible()
  expect(await page.locator('.plan-weeks article').count()).toBeGreaterThan(3)
  await expect(page.locator('.plan-weeks article dl')).toHaveCount(0)
  await page.locator('.plan-weeks article').first().getByRole('button', { name: '查看详细信息' }).click()
  await expect(page.locator('.plan-weeks article').first().locator('dl')).toBeVisible()
  await page.locator('.plan-weeks article').first().getByRole('button', { name: '提交能力证据' }).click()
  const dialog = page.locator('.verification-dialog')
  await expect(dialog).toBeVisible()
  const dialogBox = await dialog.boundingBox()
  expect(dialogBox).not.toBeNull()
  expect(dialogBox!.y).toBeGreaterThanOrEqual(0)
  expect(dialogBox!.y + dialogBox!.height).toBeLessThanOrEqual(page.viewportSize()!.height)
  await expect(dialog.locator('.el-dialog__footer')).toBeVisible()
  expect(await dialog.locator('.el-dialog__body').evaluate(element => getComputedStyle(element).overflowY)).toBe('auto')
  await page.locator('.el-dialog__footer button').last().click()
  await expect(page.locator('.plan-weeks article').first()).toContainText('待验证')
  await expect(page.locator('.plan-weeks article').first()).toContainText('审核通过前不会改变能力等级或准备度')
  await expect(page.locator('.diagnosis-comparison-v10')).toHaveCount(0)

  await page.reload({ waitUntil: 'networkidle' })
  await expect(page.locator('.plan-weeks article').first()).toContainText('待验证')
  await expect(page.locator('.plan-overview')).toContainText('准备度')
  expect(runtimeErrors).toEqual([])
})

test('student can delete the restored anonymous session completely', async ({ page }) => {
  await page.goto('/diagnosis?mode=demo&role=1', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '确认能力事实', exact: true }).click()
  await page.getByRole('button', { name: '运行双图对齐' }).click()
  await page.getByRole('button', { name: /确认能力事实.*返回修改/ }).click()
  await page.getByRole('button', { name: '返回修改', exact: true }).click()
  await page.getByRole('button', { name: '删除匿名数据' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('zhigang-career-session-v1'))).toBeNull()
})
