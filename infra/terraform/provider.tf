provider "aws" {
  region = var.region
  # 자격증명은 코드에 두지 않는다.
  # AWS CLI 설정의 default 프로필을 사용한다.
  default_tags {
    tags = {
      Team = var.team_tag
    }
  }
}
