locals {
  oidc_host = "token.actions.githubusercontent.com"

  # GitHub's default subject for a job in an environment omits the ref. The repository's OIDC
  # subject template is customised to repo + context + ref (see docs/runbooks/aws-deploy.md), so
  # only a job in this environment *and* on this branch matches; anything else fails closed.
  subject = "repo:${var.github_repository}:environment:${var.github_environment}:ref:refs/heads/${var.github_branch}"
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 1 : 0

  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_oidc_provider ? 0 : 1

  url = "https://${local.oidc_host}"
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = [local.subject]
    }
  }
}

resource "aws_iam_role" "this" {
  name                 = var.name
  description          = "Assumed by the ${var.github_repository} cd-aws workflow (${var.github_environment}, ${var.github_branch})"
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  # GetAuthorizationToken, RegisterTaskDefinition and DescribeTaskDefinition have no
  # resource-level permissions; every mutating call below is scoped to named resources.
  statement {
    sid = "ActionsWithoutResourceScope"
    actions = [
      "ecr:GetAuthorizationToken",
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"]
  }

  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = var.ecr_repository_arns
  }

  statement {
    sid       = "DeployServices"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = var.ecs_service_arns
  }

  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = var.task_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "ListConsoleBucket"
    actions   = ["s3:ListBucket"]
    resources = [var.console_bucket_arn]
  }

  statement {
    sid       = "SyncConsoleObjects"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${var.console_bucket_arn}/*"]
  }

  statement {
    sid       = "InvalidateConsoleCache"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [var.cloudfront_distribution_arn]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy-materialflow"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.deploy.json
}
