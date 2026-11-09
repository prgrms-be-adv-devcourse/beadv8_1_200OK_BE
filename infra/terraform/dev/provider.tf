# 로컬 에뮬레이터(floci) 전용이다 (#500). 운영(infra/terraform)과 state·코드가 완전히 분리돼 있어
# 여기서 apply해도 운영 리소스를 건드리지 않는다.
# 에뮬레이터는 아무 키나 받으므로 더미 키를 쓰고, 실제 AWS 확인·계정 조회는 건너뛴다.
provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Team = var.team_tag
    }
  }

  access_key                  = "test"
  secret_key                  = "test"
  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = "true"
  skip_requesting_account_id  = true

  endpoints {
    s3  = var.aws_endpoint
    sts = var.aws_endpoint
    iam = var.aws_endpoint
    ec2 = var.aws_endpoint
  }
}
