resource "aws_vpc" "materialflow" {
  count = var.enable_aws ? 1 : 0
  cidr_block = var.vpc_cidr
  enable_dns_support = true
  enable_dns_hostnames = true
  tags = {Name = "materialflow-${var.environment}"}
}

# This optional interface intentionally stops at the isolated network boundary.
# EKS, RDS, MSK and ElastiCache are not created until sizing, cost and security
# decisions receive separate authorization.
