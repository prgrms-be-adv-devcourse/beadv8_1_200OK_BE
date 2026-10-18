output "media_bucket_name" {
  description = "앱의 S3_BUCKET 환경변수에 넣는 값"
  value       = aws_s3_bucket.media.bucket
}

output "media_bucket_arn" {
  value = aws_s3_bucket.media.arn
}

output "app_public_ip" {
  description = "앱 EC2에 연결할 고정 퍼블릭 IP"
  value       = aws_eip.app.public_ip
}

output "ssh_command" {
  description = "EC2 접속 명령"
  value       = "ssh ubuntu@${aws_eip.app.public_ip}"
}
