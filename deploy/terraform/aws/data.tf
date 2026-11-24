module "postgres" {
  source = "./modules/postgres"

  name                = local.name
  vpc_id              = module.network.vpc_id
  subnet_ids          = module.network.private_subnet_ids
  engine_version      = var.postgres_engine_version
  instance_class      = var.postgres_instance_class
  multi_az            = var.postgres_multi_az
  deletion_protection = var.postgres_deletion_protection
  skip_final_snapshot = var.postgres_skip_final_snapshot
  database_name       = "materialflow"
  master_username     = "materialflow"
  kms_key_arn         = aws_kms_key.main.arn
  log_retention_days  = var.log_retention_days
}

module "kafka" {
  source = "./modules/kafka"

  name                  = local.name
  vpc_id                = module.network.vpc_id
  subnet_ids            = module.network.private_subnet_ids
  kafka_version         = var.kafka_version
  broker_instance_type  = var.kafka_broker_instance_type
  broker_volume_size_gb = var.kafka_broker_volume_size_gb
  kms_key_arn           = aws_kms_key.main.arn
  log_retention_days    = var.log_retention_days
}

module "cache" {
  source = "./modules/cache"

  name           = local.name
  vpc_id         = module.network.vpc_id
  subnet_ids     = module.network.private_subnet_ids
  engine_version = var.valkey_engine_version
  node_type      = var.valkey_node_type
  kms_key_arn    = aws_kms_key.main.arn
}
