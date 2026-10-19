data "aws_caller_identity" "current" {}

locals {
  # 리소스 이름의 공통 접두사. 바꾸면 SG·키페어·IAM 등이 교체되므로 변수가 아닌 고정값으로 둔다.
  name_prefix = "team01-200ok"

  bucket_name = "${var.bucket_prefix}-${data.aws_caller_identity.current.account_id}-${var.region}-an"
}
