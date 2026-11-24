variable "name" {
  description = "Repository namespace; repositories are named <name>/<repository>."
  type        = string
}

variable "repositories" {
  description = "Repository names to create."
  type        = set(string)
}

variable "images_to_keep" {
  description = "Number of most recent images kept per repository."
  type        = number
  default     = 15
}

variable "force_delete" {
  description = "Allow deleting repositories that still contain images."
  type        = bool
}
