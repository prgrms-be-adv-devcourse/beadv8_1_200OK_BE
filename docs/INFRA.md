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

## 다른 계정·리전으로 옮기기

1. `infra/terraform/imports.tf`를 지운다. 기존 버킷을 가져오는 용도라 새 계정에는 해당 버킷이 없다.
2. `infra/terraform/terraform.tfvars`의 `region` 등을 바꾼다. 버킷 이름은 `<bucket_prefix>-<계정ID>-<리전>-an`으로 자동 생성된다.
3. 기존 `terraform.tfstate`가 없는 새 디렉터리에 코드를 복사해서 `terraform init` → `plan` → `apply`한다. 
   같은 폴더에서 하면 기존 state를 그대로 써서 원래 계정의 리소스를 건드린다.
