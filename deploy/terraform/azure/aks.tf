# Control-plane identity, created before the cluster so it already holds its network roles when
# AKS first joins nodes to the subnet and binds the ingress IP to the load balancer.
resource "azurerm_user_assigned_identity" "aks" {
  name                = "id-${local.name}-aks"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = local.tags
}

resource "azurerm_role_assignment" "aks_subnet" {
  scope                = azurerm_subnet.aks.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "aks_ingress_ip" {
  scope                = azurerm_public_ip.ingress.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
  principal_type       = "ServicePrincipal"
}

# Accepted scanner findings (reasons inline):
#trivy:ignore:AVD-AZU-0041 Public API server without authorized IP ranges: GitHub-hosted runners have no fixed egress range. Every call needs an Entra ID token and an Azure RBAC grant; local accounts are disabled.
#trivy:ignore:AVD-AZU-0065 Not a private cluster, for the same reason (a private API server would need a self-hosted runner in the VNet).
#trivy:ignore:AVD-AZU-0040 No Azure Monitor / Log Analytics: ingestion is billed per GB and the budget is $40. Logs stay in the pods (kubectl logs) for the life of the demo.
#trivy:ignore:AVD-AZU-0066 No Azure Policy add-on: its pods would compete for a 4-vCPU app pool, and nothing here is policy-governed.
#trivy:ignore:AVD-AZU-0067 OS and data disks use platform-managed encryption at rest; a customer-managed key would need a Key Vault key and disk encryption set for synthetic data.
resource "azurerm_kubernetes_cluster" "this" {
  name                = "aks-${local.name}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  node_resource_group = "rg-${local.name}-nodes"
  dns_prefix          = local.name
  kubernetes_version  = var.kubernetes_version
  sku_tier            = "Free"

  # Upgrades would surge an extra node, and the regional quota (6 vCPUs) has no room for one.
  # Upgrade deliberately instead: runbook "Upgrades".
  automatic_upgrade_channel = null
  node_os_upgrade_channel   = "None"

  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  # Entra ID authentication with Azure RBAC for Kubernetes authorization; no static kubeconfig.
  role_based_access_control_enabled = true
  local_account_disabled            = true
  azure_active_directory_role_based_access_control {
    azure_rbac_enabled = true
    tenant_id          = data.azurerm_client_config.current.tenant_id
  }

  run_command_enabled  = false
  azure_policy_enabled = false
  # No oms_agent (Container Insights), no microsoft_defender: paid extras this demo does not need.

  default_node_pool {
    name                 = "system"
    vm_size              = var.app_vm_size
    node_count           = var.app_node_count
    vnet_subnet_id       = azurerm_subnet.aks.id
    os_sku               = "AzureLinux"
    os_disk_type         = "Managed" # Bpsv2 sizes have no local temp disk for an ephemeral OS disk
    os_disk_size_gb      = var.os_disk_size_gb
    max_pods             = var.max_pods_per_node
    auto_scaling_enabled = false
    node_labels          = { workload = "app" }
    tags                 = local.tags

    # Required by the provider for changes that rotate the pool; a rotation needs spare quota.
    temporary_name_for_rotation = "systemtmp"

    # AKS defaults, stated so plans stay clean. A surge node would not fit the vCPU quota, so
    # version upgrades are done by hand with max-surge 0 (runbook "Upgrades").
    upgrade_settings {
      max_surge                     = "10%"
      drain_timeout_in_minutes      = 0
      node_soak_duration_in_minutes = 0
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks.id]
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    pod_cidr            = "10.244.0.0/16"
    service_cidr        = "10.0.0.0/16"
    dns_service_ip      = "10.0.0.10"
    load_balancer_sku   = "standard"
    outbound_type       = "loadBalancer"
  }

  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  storage_profile {
    disk_driver_enabled         = true
    file_driver_enabled         = false
    blob_driver_enabled         = false
    snapshot_controller_enabled = false
  }

  tags = local.tags

  depends_on = [
    azurerm_role_assignment.aks_subnet,
    azurerm_role_assignment.aks_ingress_ip,
  ]
}

# Test clients only: tainted so nothing else lands here, labelled so test pods can select it.
resource "azurerm_kubernetes_cluster_node_pool" "loadgen" {
  name                  = "loadgen"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  mode                  = "User"
  vm_size               = var.loadgen_vm_size
  node_count            = var.loadgen_node_count
  auto_scaling_enabled  = false
  vnet_subnet_id        = azurerm_subnet.aks.id
  os_type               = "Linux"
  os_sku                = "AzureLinux"
  os_disk_type          = "Managed"
  os_disk_size_gb       = var.os_disk_size_gb
  max_pods              = 30
  orchestrator_version  = azurerm_kubernetes_cluster.this.kubernetes_version
  node_labels           = { workload = "loadgen" }
  node_taints           = ["workload=loadgen:NoSchedule"]
  tags                  = local.tags

  # Replace a node in place instead of surging a new one (no spare quota).
  upgrade_settings {
    max_unavailable = "1"
  }
}

# Managed Gateway API CRDs plus the application routing add-on's Gateway API implementation
# (GatewayClass approuting-istio), the AKS successor to the retired managed NGINX ingress. The
# azurerm provider has no arguments for these yet, so azapi patches just this part of the cluster.
resource "azapi_update_resource" "gateway_api" {
  type        = "Microsoft.ContainerService/managedClusters@2026-03-01"
  resource_id = azurerm_kubernetes_cluster.this.id
  body = {
    properties = {
      ingressProfile = {
        gatewayAPI = {
          installation = "Standard"
        }
        webAppRouting = {
          gatewayAPIImplementations = {
            appRoutingIstio = {
              mode = "Enabled"
            }
          }
        }
      }
    }
  }
  response_export_values = ["properties.ingressProfile"]

  depends_on = [azurerm_kubernetes_cluster_node_pool.loadgen]
}

# The signed-in operator administers the cluster through Azure RBAC.
resource "azurerm_role_assignment" "operator_cluster_admin" {
  scope                = azurerm_kubernetes_cluster.this.id
  role_definition_name = "Azure Kubernetes Service RBAC Cluster Admin"
  principal_id         = data.azurerm_client_config.current.object_id
}
