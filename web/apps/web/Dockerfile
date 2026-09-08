# sift-web: the React SPA served by nginx, proxying `/api/` to the sift-server Service (same origin, no CORS).
#
# Build context is the pnpm workspace root `web/` (see web/.dockerignore):
#
#   docker build -f apps/web/Dockerfile -t sift-web:dev web/
#   docker run --rm -p 8081:8080 -e SIFT_SERVER_UPSTREAM=http://host.docker.internal:8080 sift-web:dev
#
# Pinned multi-platform indexes; the build runs on the deploying host's architecture.
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
ENV CI=1 \
    PNPM_HOME=/pnpm \
    PATH=/pnpm:$PATH
# Same pnpm as `packageManager` in web/package.json.
RUN npm install --global --no-fund --no-audit pnpm@11.21.0
WORKDIR /workspace

# Manifests first so the dependency layer is reused while sources change.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY packages/api-client/package.json packages/api-client/
COPY apps/web/package.json apps/web/
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store \
    pnpm install --frozen-lockfile --store-dir /pnpm/store

# The api-client is consumed from source (`exports` point at src/), so only the SPA needs building.
COPY . .
RUN pnpm --filter @sift/web build

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

# The server block is a template rendered by the image entrypoint on start (envsubst, restricted to the SIFT_*
# variables and the resolver list so nginx's own `$uri`/`$host` survive). It is written to /tmp — the only
# directory that must be writable (the unprivileged image already keeps its pid file and temp paths there) —
# so the container runs with a read-only root filesystem and a single emptyDir. The stock conf.d include is
# redirected accordingly.
COPY apps/web/nginx.conf /etc/nginx/templates/default.conf.template
RUN rm /etc/nginx/conf.d/default.conf \
    && sed -i 's|include /etc/nginx/conf.d/\*.conf;|include /tmp/*.conf;|' /etc/nginx/nginx.conf \
    && grep -q 'include /tmp/\*.conf;' /etc/nginx/nginx.conf
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html

# NGINX_ENTRYPOINT_LOCAL_RESOLVERS makes the entrypoint export the nameservers of /etc/resolv.conf as
# NGINX_LOCAL_RESOLVERS, which nginx.conf uses to resolve the upstream at request time rather than at startup.
ENV SIFT_SERVER_UPSTREAM=http://sift-server:8080 \
    NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER='^(SIFT_|NGINX_LOCAL_RESOLVERS$)' \
    NGINX_ENVSUBST_OUTPUT_DIR=/tmp

# nginx-unprivileged runs as uid 101 and listens on 8080.
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
