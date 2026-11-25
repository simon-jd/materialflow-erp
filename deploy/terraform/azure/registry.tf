resource "azurerm_container_registry" "this" {
  name                = "acr${var.project}${var.environment}${local.suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  # Basic: about $0.17/day. Network rules, private endpoints and retention need Premium.
  sku           = "Basic"
  admin_enabled = false
  tags          = local.tags
}

# Nodes pull with the cluster's kubelet identity; no image pull secrets.
resource "azurerm_role_assignment" "kubelet_acr_pull" {
  scope                            = azurerm_container_registry.this.id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}

# The operator pushes the bootstrap images from a workstation.
resource "azurerm_role_assignment" "operator_acr_push" {
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPush"
  principal_id         = data.azurerm_client_config.current.object_id
}
