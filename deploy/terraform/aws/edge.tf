# Shared secret between CloudFront and the ALB: requests that do not come through this
# distribution get the listener's 403 default action.
resource "random_password" "origin_verify" {
  length  = 48
  special = false
}

module "edge" {
  source = "./modules/edge"

  name                  = local.name
  vpc_id                = module.network.vpc_id
  public_subnet_ids     = module.network.public_subnet_ids
  alb_security_group_id = aws_security_group.alb.id
  origin_verify_secret  = random_password.origin_verify.result
  console_bucket_name   = "${local.name}-console-${var.aws_account_id}"
  force_destroy         = var.force_destroy_artifacts
  price_class           = var.cloudfront_price_class
}
