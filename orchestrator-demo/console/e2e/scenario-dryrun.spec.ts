/**
 * Scenario loop: pick a scenario, execute the dry run, and verify the report
 * (validation verdict, dependency DAG, wave expansion, slot flows).
 */
import { expect, test } from '@playwright/test'
import { installBackendMocks } from './mocks'

test.beforeEach(async ({ page }) => {
  await installBackendMocks(page)
  await page.goto('/dry-run')
})

test('dry run produces a validated report with waves and slot flows', async ({ page }) => {
  // Naive UI select: open the dropdown, then pick the scenario option
  await page.locator('.n-card', { hasText: '场景推演（Dry Run）' }).locator('.n-select').click()
  await page.locator('.n-base-select-option', { hasText: '京都经典' }).click()
  await page.getByRole('button', { name: '开始推演' }).click()

  await expect(page.getByText('校验通过，可以运行')).toBeVisible()
  await expect(page.getByText('SEQUENTIAL')).toBeVisible()

  // wave expansion: stage → agent lines
  await expect(page.getByText('research', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('ItineraryAgent').first()).toBeVisible()
  await expect(page.getByText('MCP ×1')).toBeVisible()

  // slot flow table
  await expect(page.getByText('researchResponse').first()).toBeVisible()
})
