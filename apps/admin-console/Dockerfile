# syntax=docker/dockerfile:1.7
# Build context: apps/admin-console. The generated API types are committed, so the build does not
# need the OpenAPI contract from the repository root.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30-alpine
# One image for every environment: /config.js, the CSP and the optional /api proxy are written to
# /tmp/materialflow at start from environment variables (see docker/40-materialflow-runtime-config.sh).
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=0755 docker/40-materialflow-runtime-config.sh /docker-entrypoint.d/
COPY --from=build /app/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
