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

variable "infra_private_ip" {
  description = "infra EC2의 고정 private IP. private 서브넷(10.1.2.0/24) 안의 주소여야 한다. 코드에 드러나지 않게 .env의 TF_VAR_infra_private_ip로 넘긴다."
  type        = string
}
