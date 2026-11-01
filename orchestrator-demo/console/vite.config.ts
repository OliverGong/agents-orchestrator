import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Dev server proxies every backend-origin path to the Spring Boot app so the
 * SPA stays same-origin with the REST API / SSE stream (no CORS in dev).
 * In production the console container fronts nginx, which performs the same
 * reverse-proxy duty (see docker/docker-compose.yml, service `console`).
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
