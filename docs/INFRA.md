# 인프라 가이드

팀 AWS 인프라를 `infra/terraform/`의 Terraform 코드로 관리한다. (참고: #156)

## 준비

- Terraform 1.16 이상, AWS CLI
- `aws configure`로 팀 계정 자격증명 설정 (`aws sts get-caller-identity`로 계정 확인)

## 실행

```bash
cd infra/terraform
terraform init
terraform plan    # 바뀔 내용 확인
terraform apply   # plan 내용을 확인한 뒤 실행
```

## state

- state는 `infra/terraform/terraform.tfstate`에 있고 커밋하지 않는다.
- 잃어버렸으면 `infra/terraform/imports.tf`가 있는 상태로 `terraform plan` → `terraform apply`하면 기존 리소스를 다시 가져온다.
- state가 각자의 PC에 따로 있으므로 `apply`는 한 명만 한다. `apply`를 두명이 하면 state가 갈라져 리소스가 충돌하거나 중복으로 만들어진다.
- `terraform.tfvars`는 커밋되므로 비밀번호, 키 같은 비밀값을 넣지 않는다.

## 값 관리 규칙

- 계정·리전을 옮길 때 바뀌는 값은 `variables.tf`에 선언하고 `terraform.tfvars`에 넣는다. (예: `region`, `team_tag`)
- 여러 파일에서 같이 쓰는 고정값은 `locals.tf`에 둔다. (예: `name_prefix`)
- 한 곳에서만 쓰는 값은 그 자리에 쓴다. (예: 인스턴스 타입, CIDR)
- 리소스 이름과 `Name` 태그는 `"${local.name_prefix}-<용도>"`로 짓는다. 이름이 바뀌면 리소스가 교체될 수 있으므로, 이름을 정리할 때는 `plan`이 `No changes`인지 확인한다.

## 다른 계정·리전으로 옮기기

1. `infra/terraform/imports.tf`를 지운다. 기존 버킷을 가져오는 용도라 새 계정에는 해당 버킷이 없다.
2. `infra/terraform/terraform.tfvars`의 `region` 등을 바꾼다. 버킷 이름은 `<bucket_prefix>-<계정ID>-<리전>-an`으로 자동 생성된다.
3. 기존 `terraform.tfstate`가 없는 새 디렉터리에 코드를 복사해서 `terraform init` → `plan` → `apply`한다. 
   같은 폴더에서 하면 기존 state를 그대로 써서 원래 계정의 리소스를 건드린다.

## 로컬 에뮬레이터(dev)로 검증하기 (#500)

운영 AWS 대신 floci(로컬 AWS 에뮬레이터)로 S3 코드와 Terraform을 확인한다. 운영 설정은 바뀌지 않는다.
dev용 Terraform은 `infra/terraform/dev/`에 따로 있다. 코드와 state가 운영(`infra/terraform/`)과 분리돼 있어 `-state`나 `-var-file` 옵션이 필요 없고, 여기서 apply해도 운영 리소스를 건드리지 않는다.

| 구분 | 운영 | 개발(floci) |
|---|---|---|
| 앱 `app.s3.endpoint` | 없음(실제 AWS) | `S3_ENDPOINT=http://localhost:4566` |
| 앱 자격증명 | EC2 인스턴스 역할 | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`에 아무 값(`test`) |
| Terraform 위치 | `infra/terraform/` | `infra/terraform/dev/` (S3·VPC·IAM·EC2, 더미 키, 주소는 `aws_endpoint` 변수) |

```bash
# 1) floci 기동 (평소 docker compose up에는 뜨지 않는다)
docker compose --profile floci up -d floci

# 2) 버킷 만들기
cd infra/terraform/dev
terraform init
terraform apply
```

- 버킷 이름은 출력(`media_bucket_name`)에 나온다. `.env`의 `S3_BUCKET`에 넣는다. (에뮬레이터 계정 ID는 `000000000000`이라 `dev-paldo-gotgan-s3-000000000000-ap-northeast-2-an`이 된다.)
- floci 포트(`FLOCI_PORT`)를 바꿨으면 `terraform apply -var aws_endpoint=http://localhost:<포트>`로 맞춘다.
- `dev/`의 `s3.tf`, `vpc.tf`, `security_group.tf`, `iam.tf`, `ec2.tf`는 운영 파일의 복사본이다. 운영 파일을 바꾸면 이쪽도 같이 확인한다. 운영과 다른 점은 아래와 같다.
  - `prevent_destroy`를 뺐다 (S3 버킷, EBS 볼륨).
  - 보안 그룹의 SSH 허용 IP를 외부 조회(`checkip.amazonaws.com`) 대신 `127.0.0.1/32`로 고정했다.
  - EC2 `user_data`를 스크립트(`user_data.sh.tftpl`) 대신 단순 문자열로 두었다.
  - 인스턴스 타입이 `t3.medium`이다. floci가 `t3.large`를 모르는 타입으로 취급해 생성이 실패한다.
- floci는 EC2를 실제 컨테이너(`public.ecr.aws/docker/library/ubuntu:24.04`)로 띄운다. 이미지를 받다가 요청 제한(429)이 나면 인스턴스가 바로 `terminated`가 되므로, 실패하면 `docker pull public.ecr.aws/docker/library/ubuntu:24.04`로 미리 받고 `terraform apply`를 다시 한다. EC2를 만들려면 floci에 Docker 소켓이 마운트돼 있어야 한다.
- 반환 URL은 `MediaUrlResolver`가 `amazonaws.com` 주소로 만들어서 floci 주소와 다르다. 업로드·조회 호출만 확인한다.
- floci 상태는 프로젝트 루트의 `data/floci/`에 저장된다(`FLOCI_STORAGE_MODE`, 기본 `persistent`). 컨테이너를 다시 만들어도 리소스가 남으므로 `infra/terraform/dev/`의 state와 어긋나지 않게 함께 관리한다. 처음부터 다시 하려면 `docker compose --profile floci down` 뒤 `data/floci/`와 `infra/terraform/dev/terraform.tfstate*`를 같이 지운다.
- 정리: `terraform destroy` 뒤 `docker compose --profile floci down`.
