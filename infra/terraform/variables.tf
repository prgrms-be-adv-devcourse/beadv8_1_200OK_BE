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

variable "ssh_public_key" {
  description = "EC2 접속용 SSH 공개키. 레포가 공개라 끝의 주석(이메일)은 빼고 넣는다."
  type        = string
}
