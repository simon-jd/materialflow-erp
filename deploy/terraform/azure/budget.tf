# Cost guardrails. Notifications go to the subscription's Owner role, so no addresses are stored.
locals {
  budget_notifications = [
    { threshold = 50, threshold_type = "Actual" },
    { threshold = 80, threshold_type = "Actual" },
    { threshold = 100, threshold_type = "Actual" },
    { threshold = 100, threshold_type = "Forecasted" },
  ]
}

# The environment's own resource group: PostgreSQL, registry, Key Vault, public IP.
resource "azurerm_consumption_budget_resource_group" "this" {
  name              = "budget-${local.name}"
  resource_group_id = azurerm_resource_group.this.id
  amount            = var.budget_amount
  time_grain        = "Monthly"

  time_period {
    start_date = var.budget_start_date
  }

  dynamic "notification" {
    for_each = local.budget_notifications
    content {
      enabled        = true
      threshold      = notification.value.threshold
      threshold_type = notification.value.threshold_type
      operator       = "GreaterThan"
      contact_roles  = ["Owner"]
    }
  }
}

# The VMs, disks and load balancer are billed to the AKS node resource group, which the budget
# above cannot see. This one watches both groups together against the same ceiling.
resource "azurerm_consumption_budget_subscription" "environment" {
  name            = "budget-${local.name}-total"
  subscription_id = "/subscriptions/${var.subscription_id}"
  amount          = var.budget_amount
  time_grain      = "Monthly"

  time_period {
    start_date = var.budget_start_date
  }

  filter {
    dimension {
      name     = "ResourceGroupName"
      operator = "In"
      values   = [azurerm_resource_group.this.name, azurerm_kubernetes_cluster.this.node_resource_group]
    }
  }

  dynamic "notification" {
    for_each = local.budget_notifications
    content {
      enabled        = true
      threshold      = notification.value.threshold
      threshold_type = notification.value.threshold_type
      operator       = "GreaterThan"
      contact_roles  = ["Owner"]
    }
  }
}
