resource "aws_s3_bucket" "media" {
  bucket           = local.bucket_name
  bucket_namespace = "account-regional"

  tags = {
    Name = "${local.name_prefix}-s3"
  }

  # 앱이 쓰는 이미지가 들어 있어서 실수로 삭제·교체되지 않게 막는다.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_ownership_controls" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket = aws_s3_bucket.media.id

  block_public_acls       = false
  ignore_public_acls      = false
  block_public_policy     = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "media_public_read" {
  statement {
    sid     = "AllowPublicRead"
    effect  = "Allow"
    actions = ["s3:GetObject"]

    # type 이 "*" 여야 "Principal": "*" 로 렌더링된다 ("AWS"면 {"AWS":"*"})
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    resources = ["${aws_s3_bucket.media.arn}/*"]
  }
}

resource "aws_s3_bucket_policy" "media" {
  bucket = aws_s3_bucket.media.id
  policy = data.aws_iam_policy_document.media_public_read.json

  # 새로 만들 때 퍼블릭 정책 차단이 풀린 뒤에 정책을 붙여야 거부되지 않는다
  depends_on = [aws_s3_bucket_public_access_block.media]
}

resource "aws_s3_bucket_server_side_encryption_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled       = true
    blocked_encryption_types = ["SSE-C"]
  }
}
