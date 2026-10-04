output "vpc_id" {
  value = try(aws_vpc.materialflow[0].id, null)
  description = "Null while enable_aws is false. No cloud resource has been created by this repository."
}
