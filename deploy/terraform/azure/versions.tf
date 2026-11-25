terraform {
  # Ephemeral resources and write-only arguments (Terraform >= 1.11) keep every generated password
  # out of the state file: Key Vault is the only place the values are stored.
  required_version = ">= 1.11.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.81"
    }
    azapi = {
      source  = "Azure/azapi"
      version = "~> 2.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.13"
    }
  }

  # State stays on the operator's machine:
  #   terraform init -backend-config="path=$HOME/materialflow-azure/state/terraform.tfstate"
  # To move it to an Azure Storage account, see backend.hcl.example.
  backend "local" {}
}

provider "azurerm" {
  # Never fall back to the CLI's default subscription.
  subscription_id = var.subscription_id

  # Register only what this stack uses instead of the provider's default list.
  resource_provider_registrations = "none"
  resource_providers_to_register = [
    "Microsoft.ContainerRegistry",
    "Microsoft.ContainerService",
    "Microsoft.Consumption",
    "Microsoft.DBforPostgreSQL",
    "Microsoft.KeyVault",
    "Microsoft.ManagedIdentity",
    "Microsoft.Network",
  ]

  features {
    key_vault {
      # Short-lived demo environment: purge on destroy so the name can be reused at once.
      purge_soft_delete_on_destroy    = true
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
}

provider "azapi" {
  subscription_id = var.subscription_id
}
