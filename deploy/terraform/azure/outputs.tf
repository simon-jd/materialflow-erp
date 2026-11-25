locals {
  registry_prefix = "${azurerm_container_registry.this.login_server}/materialflow"
  oidc_issuer_uri = "${local.public_url}/idp/realms/materialflow"

  # Environment-specific Helm values shared by both charts: the application chart
  # (deploy/helm/materialflow) reads the top-level keys, the platform chart
  # (deploy/kubernetes/aks/platform) reads `platform`. Nothing here is secret.
  helm_environment_values = {
    global = {
      imageRegistry = local.registry_prefix
    }
    config = {
      postgresUrl   = "jdbc:postgresql://${azurerm_postgresql_flexible_server.this.fqdn}:5432/materialflow?sslmode=require"
      dbUser        = var.postgres_admin_login
      oidcIssuerUri = local.oidc_issuer_uri
    }
    serviceAccount = {
      annotations = { "azure.workload.identity/client-id" = azurerm_user_assigned_identity.workload.client_id }
    }
    adminConsole = {
      oidcAuthority = local.oidc_issuer_uri
    }
    platform = {
      publicHost     = local.public_host
      registry       = local.registry_prefix
      acrLoginServer = azurerm_container_registry.this.login_server
      keyVault = {
        name     = azurerm_key_vault.this.name
        tenantId = data.azurerm_client_config.current.tenant_id
        clientId = azurerm_user_assigned_identity.workload.client_id
      }
      postgres = {
        host   = azurerm_postgresql_flexible_server.this.fqdn
        user   = var.postgres_admin_login
        subnet = one(azurerm_subnet.postgres.address_prefixes)
      }
      ingress = {
        publicIpName  = azurerm_public_ip.ingress.name
        resourceGroup = azurerm_resource_group.this.name
      }
    }
  }
}

output "public_url" {
  description = "Single HTTPS origin: console at /, API at /api, Keycloak at /idp."
  value       = local.public_url
}

output "ingress_ip_address" {
  description = "Static public IP bound to the Gateway's load balancer."
  value       = azurerm_public_ip.ingress.ip_address
}

output "resource_group_name" {
  description = "Resource group of the environment."
  value       = azurerm_resource_group.this.name
}

output "node_resource_group_name" {
  description = "AKS-managed resource group (VMs, disks, load balancer)."
  value       = azurerm_kubernetes_cluster.this.node_resource_group
}

output "aks_cluster_name" {
  description = "AKS cluster name."
  value       = azurerm_kubernetes_cluster.this.name
}

output "aks_oidc_issuer_url" {
  description = "Cluster OIDC issuer used by the workload identity federated credentials."
  value       = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

output "acr_login_server" {
  description = "Container registry login server."
  value       = azurerm_container_registry.this.login_server
}

output "key_vault_name" {
  description = "Key Vault holding the runtime secrets."
  value       = azurerm_key_vault.this.name
}

output "postgres_fqdn" {
  description = "Private FQDN of the PostgreSQL Flexible Server."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "postgres_server_name" {
  description = "PostgreSQL Flexible Server name (for az postgres flexible-server start/stop)."
  value       = azurerm_postgresql_flexible_server.this.name
}

output "workload_identity_client_id" {
  description = "Client ID of the identity the pods use to read Key Vault."
  value       = azurerm_user_assigned_identity.workload.client_id
}

output "github_oidc_subject" {
  description = "Subject the deploy identity's federated credential trusts."
  value       = local.github_oidc_subject
}

output "get_credentials_command" {
  description = "Writes an Entra ID kubeconfig to $KUBECONFIG (kubelogin, Azure CLI token)."
  value       = "az aks get-credentials --subscription ${var.subscription_id} -g ${azurerm_resource_group.this.name} -n ${azurerm_kubernetes_cluster.this.name} --file \"$KUBECONFIG\" && kubelogin convert-kubeconfig -l azurecli"
}

output "helm_environment_values" {
  description = "Environment-specific Helm values (YAML) for the application and platform charts."
  value       = yamlencode(local.helm_environment_values)
}

output "github_actions_variables" {
  description = "Repository or azure-dev environment variables the cd-azure workflow reads (none are secrets)."
  value = {
    AZURE_CLIENT_ID         = azurerm_user_assigned_identity.github.client_id
    AZURE_TENANT_ID         = data.azurerm_client_config.current.tenant_id
    AZURE_SUBSCRIPTION_ID   = var.subscription_id
    AKS_RESOURCE_GROUP      = azurerm_resource_group.this.name
    AKS_CLUSTER_NAME        = azurerm_kubernetes_cluster.this.name
    ACR_NAME                = azurerm_container_registry.this.name
    MATERIALFLOW_PUBLIC_URL = local.public_url
    AKS_HELM_VALUES         = yamlencode(local.helm_environment_values)
  }
}
