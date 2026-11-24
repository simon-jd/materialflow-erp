resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "MSK brokers; ingress rules are granted per client in the root module"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-msk" }
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-kafka"
  kafka_versions = [var.kafka_version]

  # Two brokers in two AZs. min.insync.replicas=1 keeps acks=all producers writing while MSK
  # patches one broker at a time; a production cluster would use three AZs, RF 3 and ISR 2.
  # One partition per topic matches the local Compose topics: the consumer DLQ recoverer
  # republishes to the same partition number of <topic>.DLQ.
  # auto.create.topics.enable matches Compose; the Streams source topic is still created
  # explicitly before stream-projection starts (see the kafka-init container).
  server_properties = <<-PROPERTIES
    auto.create.topics.enable=true
    default.replication.factor=2
    min.insync.replicas=1
    num.partitions=1
    offsets.topic.replication.factor=2
    transaction.state.log.replication.factor=2
    transaction.state.log.min.isr=1
  PROPERTIES
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = length(var.subnet_ids)
  enhanced_monitoring    = "DEFAULT"

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size_gb
      }
    }
  }

  # TLS only, inside the VPC. Clients are unauthenticated at the Kafka layer and admitted by
  # security group; IAM auth would need the aws-msk-iam-auth library in every client.
  client_authentication {
    unauthenticated = true
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn

    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }
}
