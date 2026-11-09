output "media_bucket_name" {
  description = "앱 .env의 S3_BUCKET에 넣을 값"
  value       = aws_s3_bucket.media.bucket
}

output "app_public_ip" {
  description = "앱 EC2에 연결한 고정 IP"
  value       = aws_eip.app.public_ip
}

output "app_instance_id" {
  value = aws_instance.app.id
}
