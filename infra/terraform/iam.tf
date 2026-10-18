data "aws_iam_policy_document" "app_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${local.name_prefix}-app-role"
  assume_role_policy = data.aws_iam_policy_document.app_assume_role.json
}

data "aws_iam_policy_document" "app_s3" {
  statement {
    sid       = "PutMediaObjects"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.media.arn}/*"]
  }
}

resource "aws_iam_role_policy" "app_s3" {
  name   = "${local.name_prefix}-app-s3"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app_s3.json
}

resource "aws_iam_instance_profile" "app" {
  name = "${local.name_prefix}-app-profile"
  role = aws_iam_role.app.name
}