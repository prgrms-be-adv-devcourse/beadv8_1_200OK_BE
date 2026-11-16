resource "aws_vpc" "main" {
  cidr_block           = "10.1.0.0/16"
  enable_dns_hostnames = true

  tags = {
    Name = "${local.name_prefix}-vpc"
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-igw"
  }
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.1.1.0/24"
  availability_zone = "${var.region}a"

  # 퍼블릭 주소는 EIP만 쓴다. 자동 할당을 켜면 이 서브넷에 띄운 인스턴스가 의도치 않게 인터넷에 노출된다.
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-public-a"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name = "${local.name_prefix}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

# IGW로 가는 경로가 없는 서브넷. 인터넷에서 들어올 수도, 나갈 수도 없고 VPC 안(local 경로)으로만 통신한다.
resource "aws_subnet" "private" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.1.2.0/24"
  availability_zone = "${var.region}a"

  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-private-a"
  }
}

# 라우트를 따로 두지 않아 VPC 안(local) 경로만 갖는다.
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-private-rt"
  }
}

resource "aws_route_table_association" "private" {
  subnet_id      = aws_subnet.private.id
  route_table_id = aws_route_table.private.id
}

# 앱 EC2의 고정 주소. 인스턴스 연결은 EC2를 만들 때(#161) 한다.
resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "${local.name_prefix}-eip"
  }
}
