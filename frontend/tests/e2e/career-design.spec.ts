import { expect, test, type Locator, type Page } from '@playwright/test'

async function clearCareerSession(page: Page) {
  await page.addInitScript(() => sessionStorage.removeItem('zhigang-career-session-v1'))
}

async function contrastRatio(locator: Locator) {
  return locator.evaluate(element => {
    const parse = (value: string) => value.match(/[\d.]+/g)?.slice(0, 3).map(Number) ?? [0, 0, 0]
    const luminance = (channels: number[]) => {
      const [red = 0, green = 0, blue = 0] = channels.map(channel => {
        const normalized = channel / 255
        return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
    const style = getComputedStyle(element)
    const foreground = luminance(parse(style.color))
    const background = luminance(parse(style.backgroundColor))
    return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05)
  })
}

async function textColor(locator: Locator) {
  return locator.evaluate(element => getComputedStyle(element).color)
}

async function cssVariable(locator: Locator, name: string) {
  return locator.evaluate((element, variableName) => getComputedStyle(element).getPropertyValue(variableName).trim(), name)
}

test('推理标签与关键职业操作在蓝色背景上保持白字', async ({ page }) => {
  await clearCareerSession(page)
  await page.goto('/abilities?role=1', { waitUntil: 'networkidle' })
  await page.getByRole('tab', { name: '岗位画像与对齐' }).click()

  const profileAction = page.getByRole('button', { name: '建立我的能力镜像' })
  const profileActionLabel = profileAction.locator('span').first()
  await expect(profileAction).toBeVisible()
  expect(await textColor(profileActionLabel)).toBe('rgb(255, 255, 255)')
  await expect(profileAction).toHaveCSS('background-color', 'rgb(37, 99, 235)')
  expect(await cssVariable(profileAction, '--el-button-text-color')).toBe('#ffffff')
  expect(await contrastRatio(profileAction)).toBeGreaterThanOrEqual(4.5)

  await profileAction.hover()
  expect(await textColor(profileActionLabel)).toBe('rgb(255, 255, 255)')
  await expect(profileAction).toHaveCSS('background-color', 'rgb(29, 78, 216)')
  expect(await contrastRatio(profileAction)).toBeGreaterThanOrEqual(4.5)

  await profileAction.focus()
  expect(await textColor(profileActionLabel)).toBe('rgb(255, 255, 255)')
  expect(await contrastRatio(profileAction)).toBeGreaterThanOrEqual(4.5)

  await page.locator('.reasoning-tag').evaluateAll((elements) => {
    elements.forEach(element => element.remove())
    const list = document.createElement('section')
    list.className = 'evidence-list'
    list.innerHTML = '<article><header><span class="reasoning-tag">REASONING</span></header></article>'
    document.body.append(list)
  })
  expect(await textColor(page.locator('.reasoning-tag').first())).toBe('rgb(255, 255, 255)')

  await page.goto('/diagnosis?role=1', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /下一步：建立能力镜像/ }).click()
  await page.getByPlaceholder(/粘贴简历文字/).fill('本科 软件工程；掌握 Java、Spring Boot、MySQL；完成过后端项目。')
  await page.getByRole('button', { name: '识别能力事实' }).click()
  await page.getByRole('button', { name: '确认能力事实', exact: true }).click()
  await page.getByRole('button', { name: '运行双图对齐' }).click()

  const planAction = page.getByRole('button', { name: /生成 4–6 周行动计划/ })
  await expect(planAction).toBeVisible()
  expect(await textColor(planAction)).toBe('rgb(255, 255, 255)')
  expect(await cssVariable(planAction, '--el-button-text-color')).toBe('#ffffff')
})

test('工作台以清晰的三步路径引导首次职业诊断', async ({ page }) => {
  await clearCareerSession(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/workspace', { waitUntil: 'networkidle' })

  const navigation = page.viewportSize()!.width <= 860 ? page.locator('.mobile-task-nav') : page.locator('.public-task-nav')
  await expect(navigation.getByRole('link', { name: '我的工作台' })).toHaveClass(/active/)
  for (const step of ['选择岗位', '建立能力镜像', '生成行动计划']) {
    await expect(page.getByText(step, { exact: true })).toBeVisible()
  }

  const action = page.getByRole('link', { name: /开始职业诊断/ })
  await expect(action).toBeVisible()
  expect(await contrastRatio(action)).toBeGreaterThanOrEqual(4.5)
  await expect(page.locator('.career-signal-orb')).toHaveCSS('animation-name', 'none')
})

test('行动计划空态保持清晰前置路径和可读操作', async ({ page }) => {
  await clearCareerSession(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/plan', { waitUntil: 'networkidle' })

  const action = page.getByRole('button', { name: '先完成诊断' })
  await expect(action).toBeVisible()
  expect(await contrastRatio(action)).toBeGreaterThanOrEqual(4.5)
  for (const step of ['选择岗位', '建立能力镜像', '生成行动计划']) {
    await expect(page.getByText(step, { exact: true })).toBeVisible()
  }
  await expect(page.locator('.career-signal-orb')).toHaveCSS('animation-name', 'none')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('发现新岗位使用岗位雷达搜索台、完整最近卡片和信息证据双栏', async ({ page }) => {
  await page.goto('/discovery', { waitUntil: 'networkidle' })

  const radar = page.locator('.discovery-radar')
  await expect(radar).toBeVisible()
  await expect(radar.locator('.radar-scan-line')).toBeAttached()
  await expect(radar.getByText('推荐方向', { exact: true })).toBeVisible()

  const firstCard = page.locator('.recent-card').first()
  await expect(firstCard).toBeVisible()
  await expect(firstCard.locator('.recent-status')).toBeVisible()
  await expect(firstCard.locator('.recent-card__summary')).toBeVisible()
  await expect(firstCard.locator('.recent-card__metrics')).toBeVisible()
  await expect(firstCard.locator('.recent-card__action')).toContainText('查看岗位画像')

  const pageBounds = await page.locator('.discovery-page').boundingBox()
  expect(pageBounds).not.toBeNull()
  if (page.viewportSize()!.width > 860) {
    expect(pageBounds!.width).toBeGreaterThanOrEqual(1000)
    expect(await page.locator('.recent-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(3)
  } else {
    expect(await page.locator('.recent-grid').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(1)
  }

  await page.getByPlaceholder(/输入岗位或方向/).fill('大模型推理加速工程师')
  await page.getByRole('button', { name: '查询公开证据快照' }).click({ noWaitAfter: true })
  const result = page.locator('.discovery-result')
  await expect(result).toBeVisible()
  if (page.viewportSize()!.width > 860) {
    expect(await result.evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length)).toBe(2)
  }
  await expect(result.locator('.result-definition')).toBeVisible()
  await expect(result.locator('.result-evidence')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('发现新岗位在减少动态偏好下关闭查询扫描与卡片位移', async ({ page }) => {
  let releaseSnapshot = () => {}
  const snapshotHeld = new Promise<void>(resolve => { releaseSnapshot = resolve })
  await page.route('**/data/current.json', async route => {
    await snapshotHeld
    await route.continue()
  })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/discovery', { waitUntil: 'domcontentloaded' })
  await page.getByPlaceholder(/输入岗位或方向/).fill('大模型推理加速工程师')
  await page.getByRole('button', { name: '查询公开证据快照' }).click()
  await expect(page.locator('.radar-querying')).toBeVisible()
  await expect(page.locator('.radar-scan-line')).toHaveCSS('animation-name', 'none')

  releaseSnapshot()
  const card = page.locator('.recent-card').first()
  await expect(card).toBeVisible()
  await card.hover()
  expect(await card.evaluate(element => getComputedStyle(element).transform)).toBe('none')
  await card.focus()
  expect(await card.evaluate(element => getComputedStyle(element).transform)).toBe('none')
})

test('岗位演化用基线到当前的对照轨道表达变化', async ({ page }) => {
  await page.goto('/evolution?role=2', { waitUntil: 'networkidle' })

  const track = page.locator('.evolution-window-track')
  await expect(track).toBeVisible()
  await expect(track.getByText('基线', { exact: true })).toBeVisible()
  await expect(track.getByText('当前', { exact: true })).toBeVisible()
  await expect(track.getByText('→', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /查看 \d+ 条原文证据/ }).first()).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('岗位演化变化明细与证据线索提供独立可读面板', async ({ page }) => {
  await page.goto('/evolution?role=2', { waitUntil: 'networkidle' })
  await page.getByRole('tab', { name: '变化明细' }).click()
  await expect(page.locator('.evolution-change-board')).toBeVisible()
  await expect(page.locator('.evolution-change-detail').first()).toContainText('变化区间')
  await expect(page.locator('.evolution-change-detail').first()).toContainText('变化方向')
  await expect(page.locator('.evolution-change-detail').first()).toContainText('可信度')
  await expect(page.locator('.evolution-change-detail').first()).toContainText('查看原文证据')
  await page.getByRole('tab', { name: '证据线索' }).click()
  await expect(page.locator('.evolution-evidence-board')).toBeVisible()
  await expect(page.locator('.evolution-evidence-board blockquote').first()).toBeVisible()
  await expect(page.locator('.evolution-evidence-board')).toContainText(/公司|来源/)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('关系图使用蓝青和琥珀节点且资源类别不透传英文枚举', async ({ page }) => {
  await page.goto('/abilities?role=2', { waitUntil: 'networkidle' })
  await page.getByRole('tab', { name: '关系视图' }).click()
  const graph = page.locator('.relation-graph-panel')
  await expect(graph.locator('svg.graph-desktop')).toBeAttached()

  const roleFill = await graph.locator('g.graph-node').filter({ hasText: '目标岗位' }).locator('rect').first().getAttribute('fill')
  const skillFill = await graph.locator('g.graph-node').filter({ hasText: '核心技能' }).first().locator('rect').first().getAttribute('fill')
  const responsibilityFill = await graph.locator('g.graph-node').filter({ hasText: '岗位职责' }).first().locator('rect').first().getAttribute('fill')
  expect(roleFill?.toLowerCase()).toBe('#1e3a5f')
  expect(skillFill?.toLowerCase()).toBe('#e6f6fb')
  expect(responsibilityFill?.toLowerCase()).toBe('#fff4df')

  const resourceGroups = await graph.locator('.graph-resource-grid article span').allTextContents()
  expect(resourceGroups).not.toContain('COURSE')
  expect(resourceGroups).not.toContain('PROJECT')
})

test('关系图默认简洁并支持展开全部关系查看岗位职责', async ({ page }) => {
  await page.goto('/abilities?role=2', { waitUntil: 'networkidle' })
  await page.getByRole('tab', { name: '关系视图' }).click()
  const toggle = page.getByRole('button', { name: '展开全部关系' })
  await expect(toggle).toBeVisible()
  await toggle.click()
  await expect(page.getByRole('button', { name: '收起关系' })).toBeVisible()
  await expect(page.locator('.graph-mobile-group').filter({ hasText: '岗位职责' })).toBeVisible()
  await expect(page.locator('.relation-graph-panel')).not.toContainText('只展示当前岗位相关的一层关系')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
