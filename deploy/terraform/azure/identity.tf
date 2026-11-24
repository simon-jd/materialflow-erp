# --- Workload identity: pods that read Key Vault through the Secrets Store CSI driver ---------

resource "azurerm_user_assigned_identity" "workload" {
  name                = "id-${local.name}-workload"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.tags
}

# The application chart's service account and the platform chart's (Kafka, Redis, Keycloak).
resource "azurerm_federated_identity_credential" "workload" {
  for_each = toset([local.app_sa, local.platform_sa])

  name                      = "aks-${local.app_namespace}-${each.key}"
  user_assigned_identity_id = azurerm_user_assigned_identity.workload.id
  issuer                    = azurerm_kubernetes_cluster.this.oidc_issuer_url
  subject                   = "system:serviceaccount:${local.app_namespace}:${each.key}"
  audience                  = [local.azure_ad_audience]
}

# --- GitHub Actions deploy identity (cd-azure workflow) --------------------------------------

resource "azurerm_user_assigned_identity" "github" {
  name                = "id-${local.name}-github"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.tags
}

# Only jobs that run in the repository's azure-dev environment can exchange their OIDC token.
resource "azurerm_federated_identity_credential" "github" {
  name                      = "github-${var.github_environment}"
  user_assigned_identity_id = azurerm_user_assigned_identity.github.id
  issuer                    = local.oidc_token_issuer
  subject                   = local.github_oidc_subject
  audience                  = [local.azure_ad_audience]
}

# Push images to this registry only.
resource "azurerm_role_assignment" "github_acr_push" {
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
  principal_type       = "ServicePrincipal"
}

# Fetch the (Entra ID, token-less) kubeconfig of this cluster.
resource "azurerm_role_assignment" "github_cluster_user" {
  scope                = azurerm_kubernetes_cluster.this.id
  role_definition_name = "Azure Kubernetes Service Cluster User Role"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
  principal_type       = "ServicePrincipal"
}

# Read/write workloads, Services, ConfigMaps, Secrets (Helm release storage) and PDBs in the
# application namespace only; no cluster-scoped objects, no RBAC objects, no CRDs.
resource "azurerm_role_assignment" "github_namespace_writer" {
  scope                = "${azurerm_kubernetes_cluster.this.id}/namespaces/${local.app_namespace}"
  role_definition_name = "Azure Kubernetes Service RBAC Writer"
  principal_id         = azurerm_user_assigned_identity.github.principal_id
  principal_type       = "ServicePrincipal"
}
