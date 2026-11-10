data "aws_caller_identity" "current" {}

locals {
  name_prefix = "dev-200ok"

  # 운영과 같은 형식(<접두사>-<계정ID>-<리전>-an)으로 만들어 이름 규칙까지 같이 확인한다. 에뮬레이터 계정 ID는 000000000000이다
  bucket_name = "dev-paldo-gotgan-s3-${data.aws_caller_identity.current.account_id}-${var.region}-an"
}
