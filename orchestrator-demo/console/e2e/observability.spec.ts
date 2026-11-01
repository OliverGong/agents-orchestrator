/**
 * Observability closed loop: run replay (list → detail with DAG / swimlane /
 * final context), instance registry, and the team log with level filtering.
 */
import { expect, test } from '@playwright/test'
import { installBackendMocks } from './support/mocks'

test.beforeEach(async ({ page }) => {
  await installBackendMocks(page)
})

test('run replay opens the detail view with final context', async ({ page }) => {
  await page.goto('/runs')
  await page.getByRole('row', { name: /kyoto-classic/ }).click()
  await expect(page.getByText('最终上下文（黑板终态）')).toBeVisible()
  await expect(page.getByText('清水寺').first()).toBeVisible()
  await expect(page.getByText('ReviewAgent').first()).toBeVisible()
})

test('instance registry correlates the business id with stage metrics', async ({ page }) => {
  await page.goto('/instances')
  await expect(page.getByText('BIZ-42')).toBeVisible()
  await page.getByText('run-e2e-…').first().click()
  await expect(page.getByText('多 Agent / 多模型调用明细')).toBeVisible()
  await expect(page.getByText('ResearchAgent').first()).toBeVisible()
})

test('team log level filter queries the backend and filters rows', async ({ page }) => {
  await page.goto('/logs')
  await expect(page.getByText('阶段 research 完成')).toBeVisible()
  await expect(page.getByText('阶段 budget 校验失败')).toBeVisible()

  // the ERROR filter must reach the backend as a query param (Naive UI
  // select: open the dropdown, then pick the ERROR option)
  const logsRequest = page.waitForRequest((req) => req.url().includes('/api/v1/logs') && req.url().includes('level=ERROR'))
  await page.locator('.n-form-item', { hasText: '级别' }).locator('.n-select').click()
  await page.locator('.n-base-select-option', { hasText: 'ERROR' }).click()
  await logsRequest

  await expect(page.getByText('阶段 budget 校验失败')).toBeVisible()
  await expect(page.getByText('阶段 research 完成')).toBeHidden()
})
