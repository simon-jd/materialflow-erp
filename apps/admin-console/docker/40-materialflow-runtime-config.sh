#!/bin/sh
# Runs from the nginx image entrypoint before nginx starts. Writes /config.js, the security headers
# (the CSP needs the identity provider's origin) and the optional /api/ proxy from environment
# variables. Rejects anything that is not a plain URL or identifier, so no value can inject
# JavaScript or nginx directives.
#
#   OIDC_AUTHORITY  required  e.g. https://id.example.com/realms/materialflow
#   OIDC_CLIENT_ID  required  e.g. materialflow-admin-console
#   API_BASE_URL    optional  API origin; empty (default) means same origin
#   API_UPSTREAM    optional  e.g. http://edge-gateway:8081; proxies /api/ there
set -eu

out=/tmp/materialflow
mkdir -p "$out"

fail() {
    echo "materialflow-runtime-config: $*" >&2
    exit 1
}

matches() {
    printf '%s' "$1" | grep -Eq "$2"
}

origin_of() {
    printf '%s' "$1" | sed -E 's#^(https?://[^/]+).*#\1#'
}

ORIGIN='^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$'
URL='^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$'

: "${OIDC_AUTHORITY:?OIDC_AUTHORITY is required}"
: "${OIDC_CLIENT_ID:?OIDC_CLIENT_ID is required}"
API_BASE_URL=${API_BASE_URL:-}
API_UPSTREAM=${API_UPSTREAM:-}

matches "$OIDC_AUTHORITY" "$URL" || fail "OIDC_AUTHORITY must be an http(s) URL without query or fragment"
matches "$OIDC_CLIENT_ID" '^[A-Za-z0-9._-]{1,128}$' || fail "OIDC_CLIENT_ID may contain only letters, digits, '.', '_' and '-'"
[ -z "$API_BASE_URL" ] || matches "$API_BASE_URL" "$ORIGIN" || fail "API_BASE_URL must be empty or an http(s) origin"
[ -z "$API_UPSTREAM" ] || matches "$API_UPSTREAM" "$ORIGIN" || fail "API_UPSTREAM must be an http(s) origin"

cat > "$out/config.js" <<CONFIG
window.__MATERIALFLOW_CONFIG__ = Object.freeze({"apiBaseUrl":"$API_BASE_URL","oidcAuthority":"$OIDC_AUTHORITY","oidcClientId":"$OIDC_CLIENT_ID"});
CONFIG

connect_src="'self' $(origin_of "$OIDC_AUTHORITY")"
[ -z "$API_BASE_URL" ] || connect_src="$connect_src $API_BASE_URL"

# No inline script or style is needed: /config.js and the bundles are same-origin files.
cat > "$out/security-headers.conf" <<HEADERS
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src $connect_src; frame-src 'none'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "no-referrer" always;
add_header X-Frame-Options "DENY" always;
add_header Cross-Origin-Opener-Policy "same-origin" always;
add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()" always;
HEADERS

if [ -n "$API_UPSTREAM" ]; then
    cat > "$out/api-proxy.conf" <<PROXY
location /api/ {
    proxy_pass $API_UPSTREAM;
    proxy_http_version 1.1;
    # Keep the browser's Host so the gateway sees the request as same-origin and skips CORS checks.
    proxy_set_header Host \$http_host;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_set_header X-Request-Id \$request_id;
    # Identity comes only from the bearer token; never forward client-supplied identity headers.
    proxy_set_header X-Organization-Id "";
    proxy_set_header X-Actor-Id "";
    proxy_set_header X-Roles "";
    proxy_set_header X-Warehouse-Scope "";
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
    client_max_body_size 1m;
}
PROXY
else
    # The API is routed elsewhere (CloudFront, an ingress); never answer /api/ with the SPA shell.
    cat > "$out/api-proxy.conf" <<'NOPROXY'
location /api/ {
    default_type application/json;
    return 404 '{"errorCode":"NOT_ROUTED","message":"The API is not served by this host"}';
}
NOPROXY
fi

echo "materialflow-runtime-config: wrote $out (OIDC $(origin_of "$OIDC_AUTHORITY"), API ${API_BASE_URL:-same origin}${API_UPSTREAM:+ via $API_UPSTREAM})"
