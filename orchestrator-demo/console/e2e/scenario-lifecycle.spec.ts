/**
 * Lifecycle closed loop across pages: activate a scenario on the run view
 * (hot-apply to the control plane), execute the workflow through the plan
 * form, then follow the built-in link into the replay view with the run
 * preloaded via the runId route query.
 */
import { expect, test } from '@playwright/test'
import { installBackendMocks } from './mocks'

test.beforeEach(async ({ page }) => {
  await installBackendMocks(page)
})

test('activate scenario → run workflow → replay the run', async ({ page }) => {
  await page.goto('/run')

  // 1. activate the inactive scenario (大阪美食): the activate endpoint must
  //    be hit for exactly that scenario id, and the UI confirms the hot swap.
  const activateRequest = page.waitForRequest(
    (r) => r.method() === 'POST' && r.url().includes('/api/v1/scenarios/osaka-food/activate'),
  )
  await page.locator('.n-card', { hasText: '场景选择' }).locator('.n-select').click()
  await page.locator('.n-base-select-option', { hasText: '大阪美食' }).click()
  await page.getByRole('button', { name: '激活场景' }).click()
  await activateRequest
  await expect(page.getByText(/场景已激活/)).toBeVisible()

  // 2. run the workflow through the plan form; the four result cards render
  //    from the final blackboard context.
  await page.getByRole('button', { name: '开始规划' }).click()
  await expect(page.getByText('行程结论 — ReviewAgent')).toBeVisible()
  await expect(page.getByText('校验通过')).toBeVisible()
  await expect(page.getByText('鸭川').first()).toBeVisible()

  // 3. the lifecycle link jumps to the replay view with the latest run id;
  //    the timeline view folds the mocked waves into stage rows.
  await page.getByRole('button', { name: '查看运行回放 →' }).click()
  await expect(page).toHaveURL(/\/runs\?runId=run-e2e-0001/)
  await expect(page.getByText('COMPLETED').first()).toBeVisible()
  await expect(page.getByText('itinerary', { exact: true }).first()).toBeVisible()
})
