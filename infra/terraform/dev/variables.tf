variable "region" {
  description = "리소스를 만들 리전. 운영 값과 같게 둔다."
  type        = string
  default     = "ap-northeast-2"
}

variable "team_tag" {
  description = "모든 리소스에 붙는 Team 태그 값"
  type        = string
  default     = "devcos-team01"
}

variable "ssh_public_key" {
  description = "키페어에 넣을 공개키. 에뮬레이터라 접속에 쓰지 않으므로 예시 값을 둔다."
  type        = string
  default     = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDugk0eu0Vi/iVsmJYqeyrKns+pqgWIMkWgh3Udvcif1"
}

variable "aws_endpoint" {
  description = "에뮬레이터 주소. docker-compose.yml의 floci 포트(FLOCI_PORT)와 같아야 한다."
  type        = string
  default     = "http://localhost:4566"
}
