#
# Multi-stage build for the decoupled console SPA (v3.0): the TypeScript/Vite
# build runs inside a pinned Node toolchain, and the runtime image is a tiny
# nginx that serves the static bundle and reverse-proxies the backend, so the
# browser sees a single origin (no CORS, no cross-origin cookies).
# Built by docker/docker-compose.yml as the `console` service.

# ---- build stage: vue-tsc type-check + vite build inside Node ----
FROM node:22-alpine AS build
WORKDIR /build

# pnpm is provisioned by corepack, pinned via the packageManager field.
RUN corepack enable

# Dependency download layer is only invalidated when the lockfile changes.
# pnpm-workspace.yaml carries the allowBuilds approval (pnpm 12 reads build
# approvals from here, not the package.json "pnpm" field) — needed for
# strictDepBuilds to let esbuild's postinstall run.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ---- runtime stage: nginx SPA + API reverse proxy ----
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
