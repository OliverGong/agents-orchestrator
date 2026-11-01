/**
 * Live run console: the SSE blackboard receives run lifecycle events and
 * folds them into the stage list and the final context JSON.
 */
import { expect, test } from '@playwright/test'
import { installBackendMocks } from '../support/mocks'

test.beforeEach(async ({ page }) => {
  await installBackendMocks(page)
  await page.goto('/run')
})

test('SSE stream folds stages into the live blackboard', async ({ page }) => {
  // run_started over SSE renders a stable run banner (the connection tag
  // itself oscillates: the mocked stream closes after replay and EventSource
  // flips to reconnecting between replays).
  await expect(page.getByText('已启动（模板 classic · SEQUENTIAL）')).toBeVisible()

  // stage_completed events append live stage rows
  await expect(page.getByText('ResearchAgent')).toBeVisible()
  await expect(page.getByText('ItineraryAgent')).toBeVisible()

  // run_completed swaps the blackboard to the final context JSON
  await expect(page.getByText('COMPLETED')).toBeVisible()
  await expect(page.getByText('清水寺').first()).toBeVisible()
})
