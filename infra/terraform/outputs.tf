output "media_bucket_name" {
  description = "앱의 S3_BUCKET 환경변수에 넣는 값"
  value       = aws_s3_bucket.media.bucket
}

output "media_bucket_arn" {
  value = aws_s3_bucket.media.arn
}