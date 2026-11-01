/**
 * Route-by-route smoke: every console page mounts and renders its mocked
 * domain data — the functional closed loop without a live backend.
 */
import { expect, test } from '@playwright/test'
import { installBackendMocks } from './support/mocks'

test.beforeEach(async ({ page }) => {
  await installBackendMocks(page)
})

test('/run — live blackboard connects via SSE', async ({ page }) => {
  await page.goto('/run')
  // The run_started SSE frame renders a stable banner (the connection tag
  // oscillates because the mocked stream closes after replay).
  await expect(page.getByText('已启动（模板 classic · SEQUENTIAL）')).toBeVisible()
})

test('/dry-run — dry run page renders', async ({ page }) => {
  await page.goto('/dry-run')
  await expect(page.getByText('场景推演（Dry Run）')).toBeVisible()
})

test('/scenarios — scenario registry lists fixtures', async ({ page }) => {
  await page.goto('/scenarios')
  await expect(page.getByText('京都经典', { exact: true })).toBeVisible()
  await expect(page.getByText('osaka-food').first()).toBeVisible()
})

test('/agents — control plane agent configs render', async ({ page }) => {
  await page.goto('/agents')
  await expect(page.getByText('ResearchAgent').first()).toBeVisible()
  await expect(page.getByText('qwen3:8b').first()).toBeVisible()
})

test('/workflow — workflow template renders', async ({ page }) => {
  await page.goto('/workflow')
  await expect(page.getByText('SEQUENTIAL')).toBeVisible()
  await expect(page.getByText('ItineraryAgent').first()).toBeVisible()
})

test('/mcp — MCP server status renders', async ({ page }) => {
  await page.goto('/mcp')
  // "weather" lives in an editable cell; assert the status row + state text
  await expect(page.getByRole('row', { name: /weather/ })).toBeVisible()
  await expect(page.getByText('CONNECTED')).toBeVisible()
})

test('/meta — meta maintenance renders', async ({ page }) => {
  await page.goto('/meta')
  await expect(page.getByText('portal-web').first()).toBeVisible()
  // modelBackend is an n-input value, not a text node
  await expect(page.getByPlaceholder('如 qwen3:8b（空 = 后端默认）')).toHaveValue('ollama')
})

test('/instances — run instance registry renders', async ({ page }) => {
  await page.goto('/instances')
  await expect(page.getByText('BIZ-42')).toBeVisible()
})

test('/runs — run replay list renders', async ({ page }) => {
  await page.goto('/runs')
  // the list has no runId column; scenario + status identify the row
  await expect(page.getByRole('row', { name: /kyoto-classic/ })).toBeVisible()
  await expect(page.getByText('COMPLETED')).toBeVisible()
})

test('/logs — team log stream renders', async ({ page }) => {
  await page.goto('/logs')
  await expect(page.getByText('阶段 research 完成')).toBeVisible()
})

test('/metrics — system observability renders build info', async ({ page }) => {
  await page.goto('/metrics')
  await expect(page.getByText('3.0.0').first()).toBeVisible()
})
