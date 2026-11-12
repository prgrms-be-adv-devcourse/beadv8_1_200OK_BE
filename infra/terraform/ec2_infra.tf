# private_ip는 교체돼도 app의 설정이 바뀌지 않도록 고정한다. 서브넷 앞 4개(.0~.3)는 AWS 예약이라 .4 이상을 쓴다. 값은 코드에 두지 않고 .env의 TF_VAR_infra_private_ip로 받는다.
resource "aws_instance" "infra" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.private.id
  private_ip             = var.infra_private_ip
  vpc_security_group_ids = [aws_security_group.infra.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name
  key_name               = aws_key_pair.app.key_name

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 50
    encrypted   = true

    tags = {
      Name = "${local.name_prefix}-infra-root"
    }
  }

  tags = {
    Name = "${local.name_prefix}-infra"
  }

  lifecycle {
    ignore_changes = [ami]
  }
}

# 자동 생성된 ENI에는 default_tags가 붙지 않아 태그를 따로 단다.
resource "aws_ec2_tag" "infra_eni_team" {
  resource_id = aws_instance.infra.primary_network_interface_id
  key         = "Team"
  value       = var.team_tag
}

resource "aws_ec2_tag" "infra_eni_name" {
  resource_id = aws_instance.infra.primary_network_interface_id
  key         = "Name"
  value       = "${local.name_prefix}-infra-eni"
}

# 처음 medium이라는 이름으로 apply했다. state의 주소만 옮기고 인스턴스는 그대로 둔다.
moved {
  from = aws_instance.medium
  to   = aws_instance.infra
}

moved {
  from = aws_ec2_tag.medium_eni_team
  to   = aws_ec2_tag.infra_eni_team
}

moved {
  from = aws_ec2_tag.medium_eni_name
  to   = aws_ec2_tag.infra_eni_name
}
