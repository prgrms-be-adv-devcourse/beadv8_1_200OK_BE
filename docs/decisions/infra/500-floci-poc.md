---
issue: 500
module: infra
summary: floci 로컬 AWS 에뮬레이터 PoC 결과. S3·VPC·IAM·EC2는 로컬 개발과 Testcontainers 통합 테스트 모두에서 쓸 만하다
status: 진행 중
date: 2026-11-09
paths: [infra/terraform/dev, docker-compose.yml, common/src/main/java/com/ok/common/aws/S3Config.java, common/src/test/java/com/ok/common/aws]
related: [156]
---

> 이 문서는 #500 작업의 결정과 이유를 남긴 기록이다. 무엇이 바뀌었는지는 PR이 정확하고, 코드가 바뀌었으면 코드가 우선이다.

## 배경

AWS에 의존하는 기능을 실제 계정이나 Fake로만 확인하던 것을, floci(MIT 라이선스, 컨테이너로 실행하는 AWS 에뮬레이터)로 로컬에서 실제 SDK·Terraform 호출까지 확인할 수 있는지 봤다.
범위는 로컬 docker compose, 앱 S3 연결, Terraform 적용, Testcontainers 사용 가능 여부다. 도입 확정은 범위 밖으로 남겼다.
운영 설정에는 영향을 주지 않는다. 운영 `infra/terraform/`과 `application-prod.yml`은 바꾸지 않았다.

## 결정과 이유

| 결정 | 선택 | 이유 |
|---|---|---|
| 앱 연결 | `app.s3.endpoint`를 dev 프로필에만 두고, 값이 있을 때만 endpoint 변경과 path-style 적용 | 운영은 값이 없어 항상 실제 AWS와 인스턴스 역할을 쓴다 |
| 실행 방식 | compose의 `floci` 프로파일 | 평소 `docker compose up`에는 뜨지 않게 하려고 (사용자 결정) |
| Terraform | 운영과 분리한 `infra/terraform/dev/`(S3·VPC·IAM·EC2) | state·코드가 분리돼 `-state` 같은 옵션 실수로 운영 state를 건드릴 일이 없다 (사용자 결정) |
| 판단 | 로컬 개발과 통합 테스트 도입 제안, 확정은 보류 | 아래 결과 참고. 도입은 팀이 정한다 |

### 확인한 것 (되는 것)

- 앱의 `S3Config`로 만든 클라이언트로 put/get이 성공했다(임시 테스트로 확인, 커밋하지 않음).
- `infra/terraform/dev/`를 `terraform apply`해 S3·VPC·보안 그룹·IAM·키페어·EBS·EC2·EIP 32개 리소스를 만들었고, 재`plan`은 EIP의 공인 주소 표시 차이만 보였다.
- EC2는 실제 Docker 컨테이너(Ubuntu 24.04)로 뜨고, SSH(호스트 포트 연결)와 nginx 설치 후 80 포트 접속이 사용자 확인으로 됐다.
- Testcontainers 모듈(`io.floci:testcontainers-floci-aws`, 2.17.0)을 이 레포의 Testcontainers 2.0.5에 `common` 테스트 의존성으로 더하자 그대로 컴파일·실행됐다. `common/src/test/.../aws`의 `FlociS3IntegrationTest`(버킷 생성·put/get·없는 객체 예외, `S3Config`가 만든 클라이언트 사용)와 `FlociEc2IntegrationTest`(VPC·서브넷·보안 그룹, IAM 역할·인스턴스 프로파일, 인스턴스 `running`) 5건이 통과했고, `common` 전체(279건)도 그대로 통과했다.
- 웹 콘솔(UI)은 첫 접속에 사이드카를 띄운다. 사용자 확인으로 `ap-northeast-2` 설정 후 EC2가 보였다.

### 안 되거나 다른 것

- `t3.large`를 모르는 타입으로 취급해 인스턴스 생성이 실패한다. dev는 `t3.medium`을 쓴다.
- 인스턴스를 띄울 때 `public.ecr.aws`에서 이미지를 받는데, 요청 제한(429)이 나면 인스턴스가 바로 `terminated`가 된다. 미리 `docker pull` 하면 된다.
- 인스턴스 안에서 `0.0.0.0:80`은 floci가 메타데이터용으로 쓰는 프로세스와 충돌해 nginx가 뜨지 않는다. 인스턴스 자신의 주소로만 바인딩해야 한다.
- UI는 기본으로 `us-east-1`만 조회해 다른 리전의 EC2·VPC가 안 보인다. S3 목록은 리전과 무관해 보였다. `FLOCI_SERVICES_UI_EXTRA_ENV`로 리전을 맞춘다.
- `MediaUrlResolver`가 `amazonaws.com` 주소를 하드코딩해 반환 URL이 floci 주소와 맞지 않는다. 코드는 고치지 않았다.
- 운영의 `user_data`(볼륨 마운트·Docker 설치)와 앱 배포는 재현하지 않았다.

### 기동 시간과 리소스 (로컬 1회 측정)

| 항목 | 값 |
|---|---|
| floci 기동 | 네이티브 빌드, 앱 기동 로그 0.149초 |
| floci 메모리 | 유휴 약 78MiB |
| UI 사이드카 | 이미지 112MB, 메모리 약 70MiB |
| EC2 인스턴스 1대 | 메모리 약 73MiB, 이미지 78MB |
| floci 이미지 | 273MB |
| Testcontainers 테스트 | S3 클래스 약 7초(기동 포함), EC2 인스턴스 `running`까지 약 16초 |

## 버린 대안

- **Terraform 분리:** workspace는 운영 state와 같은 폴더를 써서 실행 전에 workspace를 잘못 고를 수 있어 버렸다. 감수하는 것은 `dev/`가 운영 파일의 복사본이라 운영 파일을 바꿀 때 같이 확인해야 한다는 점이다.
- **운영 Terraform에 endpoint 변수 추가:** 운영 코드와 state를 건드리지 않으려고 되돌렸다.

## 건드릴 때 주의

- `infra/terraform/dev/`의 `s3.tf`·`vpc.tf`·`security_group.tf`·`iam.tf`·`ec2.tf`는 운영 파일의 복사본이다. 운영을 바꾸면 이쪽도 확인한다.
- floci로 EC2를 만들려면 floci에 Docker 소켓이 연결돼 있어야 한다. 이 연결은 호스트 Docker 전체를 제어할 수 있어 개발 PC의 `floci` 프로파일에서만 쓴다.
- `FlociEc2IntegrationTest`의 인스턴스 생성 테스트는 외부 이미지를 받는 데 시간이 걸리고 요청 제한(429)에 걸릴 수 있어 `FLOCI_EC2_TEST=true`일 때만 실행한다. 이 환경변수 없이는 건너뛴다. 인스턴스를 띄우려면 floci 컨테이너에 Docker 소켓 연결(`withDockerSocket(true)`)이 필요하다.
- (미확인) `@ServiceConnection` 자동 연결(Spring Cloud AWS의 `spring-cloud-aws-testcontainers` 필요)과 CI 실행은 해 보지 않았다.
- 모듈 좌표는 `testcontainers-floci`에서 `testcontainers-floci-aws`로 바뀌었고 옛 좌표는 3.0.0에서 제거된다고 문서에 나와 있다.
- (미확인) 기동 시간과 메모리는 이 PC에서 1회 잰 값이라 CI 환경과 다를 수 있다.
- (미확인) 도입·보류·불채택의 최종 판단은 팀이 정한다. 위 "로컬 개발 한정 도입"은 이 PoC 기준의 제안이다.
