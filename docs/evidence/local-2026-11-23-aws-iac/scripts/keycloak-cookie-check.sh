#!/usr/bin/env bash
# Does Keycloak mark cookies Secure behind an HTTP hop (ALB) when the public URL is HTTPS, with and
# without CloudFront's "Forwarded: proto=https" origin header? Non-localhost names avoid the
# localhost secure-context exemption.
set -euo pipefail
net=mf-awscd-kc2
pw=synthetic-local-only
public=https://materialflow.example
cleanup() { docker rm -f mf-awscd-kc2 mf-awscd-pg2 >/dev/null 2>&1 || true; docker network rm $net >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network create $net >/dev/null
docker run -d --name mf-awscd-pg2 --network $net -e POSTGRES_DB=keycloak -e POSTGRES_USER=materialflow \
  -e POSTGRES_PASSWORD=$pw postgres:17.11-alpine >/dev/null
until docker exec mf-awscd-pg2 pg_isready -U materialflow -d keycloak >/dev/null 2>&1; do sleep 1; done
sleep 2
docker run -d --name mf-awscd-kc2 --network $net -p 127.0.0.1:28481:8080 -p 127.0.0.1:29481:9000 \
  -e KC_DB_URL=jdbc:postgresql://mf-awscd-pg2:5432/keycloak -e KC_DB_USERNAME=materialflow -e KC_DB_PASSWORD=$pw \
  -e KC_HOSTNAME=$public/idp -e KC_HTTP_ENABLED=true -e KC_PROXY_HEADERS=forwarded \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=bootstrap-admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=$pw \
  -e MATERIALFLOW_PUBLIC_URL=$public "${KEYCLOAK_IMAGE:-materialflow-awscd/keycloak:admin-console-realm}" >/dev/null
until curl -fsS http://127.0.0.1:29481/health/ready 2>/dev/null | grep -q '"UP"'; do sleep 2; done
cookies() {
  curl -s -o /dev/null -D - -G "http://127.0.0.1:28481/idp/realms/materialflow/protocol/openid-connect/auth" \
    -H 'Host: internal-alb-123.us-east-1.elb.amazonaws.com' -H 'X-Forwarded-Proto: http' "$@" \
    --data-urlencode client_id=materialflow-admin-console --data-urlencode response_type=code --data-urlencode scope=openid \
    --data-urlencode code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM --data-urlencode code_challenge_method=S256 \
    --data-urlencode "redirect_uri=$public/auth/callback" -w 'HTTP %{http_code}\n' |
    tr -d '\r' | grep -iE '^(set-cookie:|HTTP )' | sed -E 's/^[Ss]et-[Cc]ookie: ([^=]+)=[^;]*/\1/' | grep -E 'HTTP|AUTH_SESSION_ID' | tr '\n' ' '
}
echo "ALB-style request with Forwarded: proto=https -> $(cookies -H 'Forwarded: proto=https')"
echo "ALB-style request without Forwarded           -> $(cookies)"
echo "Non-secure context warnings logged: $(docker logs mf-awscd-kc2 2>&1 | grep -c 'Non-secure context' || true)"
