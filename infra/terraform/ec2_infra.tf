resource "aws_instance" "infra" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
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

# 서브넷이 퍼블릭 IP를 자동 할당하지 않아 EIP를 붙인다.
resource "aws_eip" "infra" {
  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-infra-eip"
  }
}

resource "aws_eip_association" "infra" {
  instance_id   = aws_instance.infra.id
  allocation_id = aws_eip.infra.id

  depends_on = [aws_internet_gateway.main]
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

moved {
  from = aws_eip.medium
  to   = aws_eip.infra
}

moved {
  from = aws_eip_association.medium
  to   = aws_eip_association.infra
}
