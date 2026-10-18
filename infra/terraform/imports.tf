# 콘솔에서 만든 기존 버킷을 state 로 가져온다.
# 다른 계정·리전에 새로 만들 때는 이 파일을 지운다.
import {
  to = aws_s3_bucket.media
  id = "team01-paldo-gotgan-s3-823315816237-ap-northeast-2-an"
}

import {
  to = aws_s3_bucket_ownership_controls.media
  id = "team01-paldo-gotgan-s3-823315816237-ap-northeast-2-an"
}

import {
  to = aws_s3_bucket_public_access_block.media
  id = "team01-paldo-gotgan-s3-823315816237-ap-northeast-2-an"
}

import {
  to = aws_s3_bucket_policy.media
  id = "team01-paldo-gotgan-s3-823315816237-ap-northeast-2-an"
}

import {
  to = aws_s3_bucket_server_side_encryption_configuration.media
  id = "team01-paldo-gotgan-s3-823315816237-ap-northeast-2-an"
}