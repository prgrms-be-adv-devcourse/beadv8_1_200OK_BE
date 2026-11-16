data "http" "my_ip" {
  url = "https://checkip.amazonaws.com"

  lifecycle {
    postcondition {
      condition     = self.status_code == 200 && can(regex("^(\\d{1,3}\\.){3}\\d{1,3}$", chomp(self.response_body)))
      error_message = "checkip.amazonaws.com에서 IPv4 주소를 받지 못했다."
    }
  }
}

resource "aws_security_group" "app" {
  name        = "${local.name_prefix}-app-sg"
  description = "paldo-gotgan app server"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-app-sg"
  }
}

resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "${chomp(data.http.my_ip.response_body)}/32"
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# 규칙 블록을 두지 않아 기본 SG의 규칙을 모두 지운다. SG를 지정하지 않은 인스턴스가 열린 규칙을 쓰지 않게 하기 위함이다.
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-default-sg"
  }
}

# private 서브넷의 infra 인스턴스용. 인터넷에서 들어오는 규칙은 두지 않고 app SG에서 오는 SSH만 허용한다.
resource "aws_security_group" "infra" {
  name        = "${local.name_prefix}-infra-sg"
  description = "paldo-gotgan infra server"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-infra-sg"
  }
}

# app을 거쳐(ProxyJump) infra에 SSH로 접속한다.
resource "aws_vpc_security_group_ingress_rule" "infra_ssh_from_app" {
  security_group_id            = aws_security_group.infra.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 22
  to_port                      = 22
  ip_protocol                  = "tcp"
}

# infra의 Prometheus가 app 서버의 exporter를 긁어 간다(mysql·redis·kafka는 app 서버의 compose에서 돈다).
# 방향이 infra -> app이라 규칙은 app SG에 둔다. 수집 대상이 늘면 포트를 추가한다.
locals {
  exporter_ports = {
    mysql = 9104
    redis = 9121
    kafka = 9308
  }
}

resource "aws_vpc_security_group_ingress_rule" "app_exporter_from_infra" {
  for_each = local.exporter_ports

  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.infra.id
  from_port                    = each.value
  to_port                      = each.value
  ip_protocol                  = "tcp"
  description                  = "${each.key} exporter"
}

# NAT가 없어 인터넷으로는 나가지 못하지만, 나중에 NAT를 붙일 때 규칙을 다시 만지지 않도록 app과 같이 전체 허용한다.
resource "aws_vpc_security_group_egress_rule" "infra_all" {
  security_group_id = aws_security_group.infra.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
