config {
  # Lint the local modules through their calls from the root, with real argument values.
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
  # deep_check would call AWS APIs to validate IDs; it needs credentials and stays off.
}
