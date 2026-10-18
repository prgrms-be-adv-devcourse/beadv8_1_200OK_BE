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
  name        = "team01-200ok-app-sg"
  description = "paldo-gotgan app server"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "team01-200ok-app-sg"
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
    Name = "team01-200ok-default-sg"
  }
}
