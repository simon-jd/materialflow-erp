resource "azurerm_postgresql_flexible_server" "this" {
  name                = "psql-${local.name}-${local.suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  version             = "17"
  sku_name            = var.postgres_sku_name
  storage_mb          = var.postgres_storage_mb
  storage_tier        = "P4"
  auto_grow_enabled   = false

  # Private access only: VNet-integrated, resolvable through the linked private DNS zone.
  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id
  public_network_access_enabled = false

  administrator_login = var.postgres_admin_login
  # Write-only: read from Key Vault at apply time and never stored in the Terraform state.
  administrator_password_wo         = ephemeral.azurerm_key_vault_secret.postgres_admin_password.value
  administrator_password_wo_version = var.secret_version

  authentication {
    password_auth_enabled         = true
    active_directory_auth_enabled = false
  }

  backup_retention_days        = 7
  geo_redundant_backup_enabled = false

  tags = local.tags

  lifecycle {
    # westus has no availability zones; Azure reports whatever it placed the server in.
    ignore_changes = [zone]
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]
}

# TLS is mandatory for every client (the JDBC URLs use sslmode=require); connection and
# checkpoint logging and connection throttling stay on in the server's own logs.
resource "azurerm_postgresql_flexible_server_configuration" "this" {
  for_each = {
    require_secure_transport     = "on"
    log_connections              = "on"
    log_checkpoints              = "on"
    "connection_throttle.enable" = "on"
  }

  name      = each.key
  server_id = azurerm_postgresql_flexible_server.this.id
  value     = each.value
}

# The Flyway migrations (apps/erp-core/src/main/resources/db/migration) need no extensions, so
# azure.extensions stays at its empty default. The fault drills need plpgsql trigger functions
# and advisory locks, which the administrator role already has on the tables it owns.

resource "azurerm_postgresql_flexible_server_database" "materialflow" {
  name      = "materialflow"
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_database" "keycloak" {
  name      = "keycloak"
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}
