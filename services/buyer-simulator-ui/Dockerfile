# syntax=docker/dockerfile:1

# ── Build stage ──────────────────────────────────────────────────────────────
FROM node:20-alpine AS builder
WORKDIR /workspace

# Vite inlines env vars at build time. The browser later calls the gateway at
# this URL from the host, so the default targets the docker-compose port.
ARG VITE_GATEWAY_BASE_URL=http://localhost:8080
ENV VITE_GATEWAY_BASE_URL=${VITE_GATEWAY_BASE_URL}

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ── Runtime stage ────────────────────────────────────────────────────────────
# nginxinc/nginx-unprivileged runs as uid 101 and listens on 8080 by default —
# no custom user/PID-path plumbing needed. We replace the default server block
# to add SPA fallback for client-side routes.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=builder /workspace/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8080/ || exit 1
