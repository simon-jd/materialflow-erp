config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "azurerm" {
  enabled = true
  version = "0.33.0"
  source  = "github.com/terraform-linters/tflint-ruleset-azurerm"
}

# prevent_destroy on Key Vault, its secrets and PostgreSQL would make `terraform destroy` fail,
# and this short-lived demo environment is meant to be torn down with one command (runbook
# "Teardown"). The data is synthetic and the schema is recreated by Flyway.
rule "azurerm_resources_missing_prevent_destroy" {
  enabled = false
}
