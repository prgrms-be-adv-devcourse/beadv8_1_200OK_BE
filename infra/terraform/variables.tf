variable "region" {
  description = "리소스를 만들 AWS 리전"
  type        = string
}

variable "team_tag" {
  description = "모든 리소스에 붙는 Team 태그 값"
  type        = string
}

variable "bucket_prefix" {
  description = "미디어 버킷 이름 앞부분. 뒤에 -<계정ID>-<리전>-an 이 붙는다."
  type        = string
}

variable "bucket_name_tag" {
  description = "미디어 버킷의 Name 태그 값"
  type        = string
}
