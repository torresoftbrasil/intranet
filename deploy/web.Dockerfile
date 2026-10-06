FROM node:24-bookworm-slim AS build
WORKDIR /build/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci
COPY apps/web/ ./
RUN npm run build
FROM nginx:stable-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/apps/web/dist/intranet-web/browser/ /usr/share/nginx/html/
EXPOSE 80
