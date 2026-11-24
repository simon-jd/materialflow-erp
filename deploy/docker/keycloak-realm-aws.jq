# Used by Dockerfile.keycloak. For the admin console client, add every localhost redirect URI's
# path on ${MATERIALFLOW_PUBLIC_URL}: Keycloak resolves that placeholder from the environment when
# it imports the realm, because the CloudFront host name only exists after terraform apply.
# Clients other than materialflow-admin-console are left untouched.
def public: sub("^https?://[^/]+"; "${MATERIALFLOW_PUBLIC_URL}");

(.clients[] | select(.clientId == "materialflow-admin-console")) |= (
  .redirectUris = (.redirectUris + [.redirectUris[] | public] | unique)
  | .webOrigins = ((.webOrigins // []) + ["${MATERIALFLOW_PUBLIC_URL}"] | unique)
  | if .attributes["post.logout.redirect.uris"] then
      .attributes["post.logout.redirect.uris"] |= (split("##") | . + map(public) | unique | join("##"))
    else . end
)
