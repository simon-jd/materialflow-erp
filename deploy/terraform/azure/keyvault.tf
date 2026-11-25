# Runtime secrets live only in Key Vault. Terraform generates them as ephemeral values and writes
# them with write-only arguments, so neither the passwords nor their hashes reach the state file.
# Pods read them through the Secrets Store CSI driver with workload identity (identity.tf).

#trivy:ignore:AVD-AZU-0016 Purge protection is off on purpose: the environment is torn down and recreated, and a purge-protected vault would block its own name for 7 days. Soft delete still keeps deleted secrets for 7 days.
resource "azurerm_key_vault" "this" {
  name                       = "kv-mf-${var.environment}-${local.suffix}"
  location                   = azurerm_resource_group.this.location
  resource_group_name        = azurerm_resource_group.this.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false
  soft_delete_retention_days = 7

  # Firewall: the AKS node subnet (service endpoint) and the operator's address only.
  public_network_access_enabled = true
  network_acls {
    default_action             = "Deny"
    bypass                     = "AzureServices"
    ip_rules                   = var.operator_ip_addresses
    virtual_network_subnet_ids = [azurerm_subnet.aks.id]
  }

  tags = local.tags
}

resource "azurerm_role_assignment" "operator_kv_secrets_officer" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "workload_kv_secrets_user" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.workload.principal_id
  principal_type       = "ServicePrincipal"
}

# Azure RBAC grants take a minute or two to reach Key Vault's data plane.
resource "time_sleep" "kv_rbac_propagation" {
  create_duration = "90s"
  depends_on      = [azurerm_role_assignment.operator_kv_secrets_officer]
}

# Secrets expire after a year; bump var.secret_version to rotate earlier.
resource "time_offset" "secret_expiry" {
  offset_days = 365
  triggers    = { version = var.secret_version }
}

ephemeral "random_password" "postgres_admin" {
  length      = 32
  special     = false
  min_upper   = 2
  min_lower   = 2
  min_numeric = 2
}

ephemeral "random_password" "keycloak_admin" {
  length      = 32
  special     = false
  min_upper   = 2
  min_lower   = 2
  min_numeric = 2
}

#trivy:ignore:AVD-AZU-0017 expiration_date is set from time_offset.secret_expiry, which the scanner cannot resolve statically.
resource "azurerm_key_vault_secret" "postgres_admin_password" {
  name             = "postgres-admin-password"
  key_vault_id     = azurerm_key_vault.this.id
  value_wo         = ephemeral.random_password.postgres_admin.result
  value_wo_version = var.secret_version
  content_type     = "PostgreSQL administrator password (${var.postgres_admin_login})"
  expiration_date  = time_offset.secret_expiry.rfc3339
  tags             = local.tags

  depends_on = [time_sleep.kv_rbac_propagation]
}

# Keycloak creates this master-realm administrator on first start. /idp/admin is not routed by
# the Gateway, so the account is reachable only from inside the cluster.
#trivy:ignore:AVD-AZU-0017 expiration_date is set from time_offset.secret_expiry, which the scanner cannot resolve statically.
resource "azurerm_key_vault_secret" "keycloak_admin_password" {
  name             = "keycloak-bootstrap-admin-password"
  key_vault_id     = azurerm_key_vault.this.id
  value_wo         = ephemeral.random_password.keycloak_admin.result
  value_wo_version = var.secret_version
  content_type     = "Keycloak bootstrap administrator password"
  expiration_date  = time_offset.secret_expiry.rfc3339
  tags             = local.tags

  depends_on = [time_sleep.kv_rbac_propagation]
}

# Read back at apply time so the server always gets exactly the value stored in Key Vault, even if
# an earlier apply stopped between writing the secret and creating the server.
ephemeral "azurerm_key_vault_secret" "postgres_admin_password" {
  name         = azurerm_key_vault_secret.postgres_admin_password.name
  key_vault_id = azurerm_key_vault.this.id
  depends_on   = [azurerm_key_vault_secret.postgres_admin_password]
}
