# FileGrid frontend: Angular build -> static files served by nginx (non-root, port 8080).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# npm ci rejects the lock file on Linux (cross-platform optional wasm deps), so install from it instead.
RUN npm install --no-audit --no-fund
COPY . .
RUN npx ng build --configuration production

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/filegrid-frontend/browser /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
