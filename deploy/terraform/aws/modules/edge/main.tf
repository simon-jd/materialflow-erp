locals {
  origin_verify_header = "X-Origin-Verify"

  # Paths CloudFront forwards to the ALB; everything else is the admin console in S3.
  alb_path_patterns = ["/api/*", "/idp/*", "/actuator/health*"]
}

# --- Application Load Balancer -------------------------------------------------------------

# Internet-facing because CloudFront custom origins must be public, but its security group only
# admits CloudFront's origin-facing ranges and every forwarding rule requires the secret header
# that only this distribution sends.
#trivy:ignore:AVD-AWS-0053
resource "aws_lb" "this" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = var.public_subnet_ids
  security_groups            = [var.alb_security_group_id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "gateway" {
  name                 = "${var.name}-gateway"
  port                 = 8081
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "keycloak" {
  name                 = "${var.name}-keycloak"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  # Keycloak serves health on its separate management port.
  health_check {
    path                = "/health/ready"
    port                = "9000"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Plain HTTP between CloudFront and the ALB: viewers always use HTTPS, but an HTTPS origin needs
# a custom domain and ACM certificate, which this demo environment deliberately does not own.
#trivy:ignore:AVD-AWS-0054
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Forbidden"
      status_code  = "403"
    }
  }
}

# Keycloak's admin console/API and the master realm are never published.
resource "aws_lb_listener_rule" "block_keycloak_admin" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    path_pattern {
      values = ["/idp/admin/*", "/idp/realms/master/*"]
    }
  }

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not Found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "keycloak" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 20

  condition {
    http_header {
      http_header_name = local.origin_verify_header
      values           = [var.origin_verify_secret]
    }
  }

  condition {
    path_pattern {
      values = ["/idp/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.keycloak.arn
  }
}

resource "aws_lb_listener_rule" "gateway" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 30

  condition {
    http_header {
      http_header_name = local.origin_verify_header
      values           = [var.origin_verify_secret]
    }
  }

  condition {
    path_pattern {
      values = ["/api/*", "/actuator/health", "/actuator/health/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

# --- Admin console bucket ---------------------------------------------------------------------

# Holds only the compiled console; viewer requests are already visible at CloudFront, so S3
# server access logs would add storage without information.
#trivy:ignore:AVD-AWS-0089
resource "aws_s3_bucket" "console" {
  bucket        = var.console_bucket_name
  force_destroy = var.force_destroy
}

resource "aws_s3_bucket_public_access_block" "console" {
  bucket                  = aws_s3_bucket.console.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "console" {
  bucket = aws_s3_bucket.console.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "console" {
  bucket = aws_s3_bucket.console.id

  versioning_configuration {
    status = "Enabled"
  }
}

# The bucket only holds the compiled, publicly served SPA; SSE-S3 avoids granting CloudFront
# decrypt rights on the environment key.
#trivy:ignore:AVD-AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "console" {
  bucket = aws_s3_bucket.console.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "console" {
  bucket = aws_s3_bucket.console.id

  rule {
    id     = "expire-replaced-builds"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "console_bucket" {
  statement {
    sid       = "CloudFrontOriginAccessControlRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.console.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.console.arn, "${aws_s3_bucket.console.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "console" {
  bucket = aws_s3_bucket.console.id
  policy = data.aws_iam_policy_document.console_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.console]
}

# --- CloudFront: one HTTPS origin for the SPA and the API ------------------------------------

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header (including Authorization) and cookie except Host.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_origin_access_control" "console" {
  name                              = "${var.name}-console"
  description                       = "Signed requests from CloudFront to the private console bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# SPA deep links (/orders/123) have no file extension; serve index.html for them. A function on
# the console behavior only, rather than a 403/404 error page, keeps API errors intact.
resource "aws_cloudfront_function" "spa_routing" {
  name    = "${var.name}-spa-routing"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite extensionless console paths to /index.html"
  publish = true
  code    = file("${path.module}/spa-routing.js")
}

# Demo trade-offs, documented in docs/adr/0009-aws-ecs-fargate-deployment.md: access logging is
# off to avoid log storage (AVD-AWS-0010), and WAF is omitted for cost; the gateway applies
# Redis-backed rate limits instead (AVD-AWS-0011). The default *.cloudfront.net certificate
# also means the minimum viewer TLS version cannot be raised without a custom domain.
#trivy:ignore:AVD-AWS-0010
#trivy:ignore:AVD-AWS-0011
resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = "${var.name}: admin console and API on one origin"
  default_root_object = "index.html"
  price_class         = var.price_class
  http_version        = "http2and3"
  is_ipv6_enabled     = true

  origin {
    origin_id                = "console"
    domain_name              = aws_s3_bucket.console.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.console.id
  }

  origin {
    origin_id   = "alb"
    domain_name = aws_lb.this.dns_name

    custom_header {
      name  = local.origin_verify_header
      value = var.origin_verify_secret
    }

    # The ALB rewrites X-Forwarded-Proto to "http" (its listener protocol) but passes RFC 7239
    # Forwarded through. CloudFront overwrites any viewer-supplied value, so Keycloak can trust
    # it to treat requests as HTTPS and mark its cookies Secure.
    custom_header {
      name  = "Forwarded"
      value = "proto=https"
    }

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id           = "console"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_routing.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.alb_path_patterns

    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "alb"
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      compress                 = true
      cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
