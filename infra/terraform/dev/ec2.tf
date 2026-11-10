data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_key_pair" "app" {
  key_name   = "${local.name_prefix}-key"
  public_key = var.ssh_public_key
}

resource "aws_ebs_volume" "mysql" {
  availability_zone = aws_subnet.public.availability_zone
  size              = 20
  type              = "gp3"
  encrypted         = true

  tags = {
    Name = "${local.name_prefix}-mysql-data"
  }

}

resource "aws_instance" "app" {
  ami = data.aws_ami.ubuntu.id
  # 운영은 t3.large. floci가 t3.large를 모르는 타입으로 취급해(describe-instance-types 빈 결과) 생성이 실패해서 지원되는 t3.medium으로 낮춘다
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name
  key_name               = aws_key_pair.app.key_name

  # 운영은 user_data.sh.tftpl(볼륨 마운트·docker 설치)을 쓴다. 에뮬레이터는 스크립트를 실행하지 않을 수 있어 단순 문자열로 둔다
  user_data = "#!/bin/bash\necho dev"

  # 컨테이너 안의 앱이 인스턴스 역할 자격증명을 받으려면 hop limit이 2 이상이어야 한다.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  # unlimited(기본값)는 크레딧이 바닥나면 초과분을 과금한다. 예산 알림 권한이 없어 느려지는 쪽을 택한다.
  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 30
    encrypted   = true

    tags = {
      Name = "${local.name_prefix}-app-root"
    }
  }

  tags = {
    Name = "${local.name_prefix}-app"
  }

  # 새 Ubuntu AMI가 나올 때마다 인스턴스가 교체되지 않게 처음 AMI로 고정한다.
  # user_data는 첫 부팅에만 실행되므로, 스크립트를 고쳐도 기존 인스턴스를 중지·재시작하지 않고 새 인스턴스에만 적용한다.
  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

# EC2가 자동으로 만든 ENI에는 default_tags가 붙지 않아 Team 태그를 따로 단다.
resource "aws_ec2_tag" "app_eni_team" {
  resource_id = aws_instance.app.primary_network_interface_id
  key         = "Team"
  value       = var.team_tag
}

resource "aws_ec2_tag" "app_eni_name" {
  resource_id = aws_instance.app.primary_network_interface_id
  key         = "Name"
  value       = "${local.name_prefix}-app-eni"
}

resource "aws_volume_attachment" "mysql" {
  device_name                    = "/dev/sdf"
  volume_id                      = aws_ebs_volume.mysql.id
  instance_id                    = aws_instance.app.id
  stop_instance_before_detaching = true
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id

  # IGW가 VPC에 붙기 전에 연결하면 실패할 수 있는데, 둘 사이에 참조가 없어 순서를 직접 지정한다.
  depends_on = [aws_internet_gateway.main]
}
