data "azurerm_client_config" "current" {}

# Globally unique names (registry, Key Vault, PostgreSQL) get a short random suffix.
resource "random_string" "suffix" {
  length  = 5
  upper   = false
  special = false
}

locals {
  name   = "${var.project}-${var.environment}"
  suffix = random_string.suffix.result

  tags = {
    project     = "materialflow-erp"
    environment = var.environment
    managed-by  = "terraform"
    repository  = var.github_repository
  }

  public_host = "${var.dns_label}.${var.location}.cloudapp.azure.com"
  public_url  = "https://${local.public_host}"

  # Kubernetes names shared by Terraform (federated credentials) and the Helm values it renders.
  app_namespace     = "materialflow"
  app_sa            = "materialflow"
  platform_sa       = "materialflow-platform"
  oidc_token_issuer = "https://token.actions.githubusercontent.com"
  azure_ad_audience = "api://AzureADTokenExchange"
  # GitHub repositories with immutable OIDC subjects (`use_immutable_subject`) put the owner and
  # repository IDs into the subject: repo:<owner>@<owner_id>/<repo>@<repo_id>:environment:<env>.
  github_subject_repository = var.github_repository_ids == null ? var.github_repository : format(
    "%s@%d/%s@%d", split("/", var.github_repository)[0], var.github_repository_ids.owner_id,
  split("/", var.github_repository)[1], var.github_repository_ids.repository_id)
  github_oidc_subject = "repo:${local.github_subject_repository}:environment:${var.github_environment}"
}

resource "azurerm_resource_group" "this" {
  name     = "rg-${local.name}"
  location = var.location
  tags     = local.tags
}
