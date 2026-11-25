variable "subscription_id" {
  description = "Azure subscription that holds the environment. Passed explicitly so the CLI default is never used."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", var.subscription_id))
    error_message = "subscription_id must be a subscription GUID."
  }
}

variable "location" {
  description = "Azure region. The subscription policy allows only the regions listed in the validation."
  type        = string
  default     = "westus"

  validation {
    condition     = contains(["westus", "norwayeast", "francecentral", "denmarkeast", "swedencentral"], var.location)
    error_message = "location must be a region the subscription policy allows."
  }
}

variable "project" {
  description = "Project tag value and name stem."
  type        = string
  default     = "materialflow"
}

variable "environment" {
  description = "Environment name used in resource names."
  type        = string
  default     = "dev"
}

variable "github_repository" {
  description = "owner/name of the repository whose workflow deploys this environment."
  type        = string
  default     = "simon-jd/materialflow-erp"
}

variable "github_repository_ids" {
  description = <<-EOT
    Numeric owner and repository IDs, needed when the repository uses immutable OIDC subjects
    (gh api repos/OWNER/REPO/actions/oidc/customization/sub shows use_immutable_subject).
    null keeps the classic subject repo:OWNER/REPO:environment:ENV.
  EOT
  type = object({
    owner_id      = number
    repository_id = number
  })
  default = {
    owner_id      = 276495489
    repository_id = 1368743399
  }
}

variable "github_environment" {
  description = "GitHub Actions environment the deploy identity trusts."
  type        = string
  default     = "azure-dev"
}

# --- AKS -------------------------------------------------------------------------------------

variable "kubernetes_version" {
  description = "AKS Kubernetes minor version (the newest GA minor in the region when this was written). Auto-upgrade is off."
  type        = string
  default     = "1.37"
}

variable "app_vm_size" {
  description = "VM size of the system/app pool. Arm64 Bpsv2: the x86 sizes this subscription has quota for are not offered to it in westus (see ADR 0012)."
  type        = string
  default     = "Standard_B2ps_v2"
}

variable "app_node_count" {
  description = "Nodes in the system/app pool."
  type        = number
  default     = 2
}

variable "loadgen_vm_size" {
  description = "VM size of the tainted load-generator pool (test clients only)."
  type        = string
  default     = "Standard_B2pls_v2"
}

variable "loadgen_node_count" {
  description = "Nodes in the load-generator pool; 0 keeps the pool but runs no VM."
  type        = number
  default     = 1

  validation {
    condition     = var.loadgen_node_count >= 0 && var.loadgen_node_count <= 1
    error_message = "The regional quota is 6 vCPUs: 2 app nodes plus at most 1 load-generator node."
  }
}

variable "max_pods_per_node" {
  description = "Kubelet max pods. Kept low because AKS reserves memory per possible pod (20 MiB each)."
  type        = number
  default     = 60
}

variable "os_disk_size_gb" {
  description = "Managed OS disk size per node (Premium SSD; 32 GiB is a P4)."
  type        = number
  default     = 32
}

# --- PostgreSQL ------------------------------------------------------------------------------

variable "postgres_sku_name" {
  description = "Flexible Server SKU (General Purpose D2ds_v5; B_Standard_B2s is the burstable fallback)."
  type        = string
  default     = "GP_Standard_D2ds_v5"
}

variable "postgres_storage_mb" {
  description = "Flexible Server storage in MiB (32 GiB = P4)."
  type        = number
  default     = 32768
}

variable "postgres_admin_login" {
  description = "Administrator login. The applications, Keycloak and the fault drills use it."
  type        = string
  default     = "mfadmin"
}

variable "secret_version" {
  description = "Bump to generate and store new random passwords (PostgreSQL admin, Keycloak bootstrap admin). Pods pick them up on restart."
  type        = number
  default     = 1
}

# --- Edge, access, budget --------------------------------------------------------------------

variable "dns_label" {
  description = "DNS label of the ingress public IP: <label>.<region>.cloudapp.azure.com."
  type        = string
  default     = "materialflow-erp"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,61}[a-z0-9]$", var.dns_label))
    error_message = "dns_label must be 3-63 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "operator_ip_addresses" {
  description = "Public IPv4 addresses allowed through the Key Vault firewall (bare addresses, no /32)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for ip in var.operator_ip_addresses : can(regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$", ip))])
    error_message = "operator_ip_addresses must be bare IPv4 addresses."
  }
}

variable "budget_amount" {
  description = "Monthly cost budget in USD for the environment's resource groups."
  type        = number
  default     = 40
}

variable "budget_start_date" {
  description = "First day of the first budget month (RFC 3339, must be the first of a month)."
  type        = string
  default     = "2026-11-01T00:00:00Z"
}
