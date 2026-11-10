package com.ok.common.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.floci.testcontainers.FlociContainer;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.iam.IamClient;

// 운영 인프라(infra/terraform)와 같은 종류의 리소스를 floci에 만들 수 있는지 확인한다 (#500)
@Testcontainers
class FlociEc2IntegrationTest {

	private static final String ASSUME_ROLE_POLICY = """
			{"Version":"2012-10-17","Statement":[{"Effect":"Allow",
			"Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";

	// 인스턴스를 만들면 floci가 Docker 소켓으로 컨테이너를 띄우므로 소켓을 연결한다
	@Container
	static FlociContainer floci = new FlociContainer()
			.withRegion("ap-northeast-2")
			.withDefaultAvailabilityZone("ap-northeast-2a")
			.withDockerSocket(true);

	private Ec2Client ec2Client() {
		return Ec2Client.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
				.build();
	}

	private IamClient iamClient() {
		return IamClient.builder()
				.endpointOverride(URI.create(floci.getEndpoint()))
				.region(Region.of(floci.getRegion()))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(floci.getAccessKey(), floci.getSecretKey())))
				.build();
	}

	@Test
	void VPC와_서브넷과_보안그룹을_만든다() {
		// given
		Ec2Client sut = ec2Client();

		// when
		String vpcId = sut.createVpc(r -> r.cidrBlock("10.1.0.0/16")).vpc().vpcId();
		String subnetId = sut.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.1.1.0/24")
				.availabilityZone("ap-northeast-2a")).subnet().subnetId();
		String sgId = sut.createSecurityGroup(r -> r.vpcId(vpcId).groupName("poc-app-sg")
				.description("poc")).groupId();

		// then
		assertThat(sut.describeSubnets(r -> r.subnetIds(subnetId)).subnets()).hasSize(1);
		assertThat(sut.describeSecurityGroups(r -> r.groupIds(sgId)).securityGroups()).hasSize(1);
	}

	@Test
	void IAM_역할과_인스턴스_프로파일을_만든다() {
		// given
		IamClient sut = iamClient();

		// when
		sut.createRole(r -> r.roleName("poc-app-role").assumeRolePolicyDocument(ASSUME_ROLE_POLICY));
		sut.putRolePolicy(r -> r.roleName("poc-app-role").policyName("poc-app-s3")
				.policyDocument("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
						+ "\"Action\":\"s3:PutObject\",\"Resource\":\"*\"}]}"));
		sut.createInstanceProfile(r -> r.instanceProfileName("poc-app-profile"));
		sut.addRoleToInstanceProfile(r -> r.instanceProfileName("poc-app-profile").roleName("poc-app-role"));

		// then
		var profile = sut.getInstanceProfile(r -> r.instanceProfileName("poc-app-profile")).instanceProfile();
		assertThat(profile).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(profile.roles()).hasSize(1);
			softly.assertThat(profile.roles().get(0).roleName()).isEqualTo("poc-app-role");
		});
	}

	// 외부 레지스트리에서 이미지를 받아 인스턴스를 컨테이너로 띄우므로 시간이 걸리고 요청 제한(429)에 걸릴 수 있다
	// 그래서 FLOCI_EC2_TEST=true 일 때만 실행한다
	@Test
	@EnabledIfEnvironmentVariable(named = "FLOCI_EC2_TEST", matches = "true")
	void 인스턴스를_만들면_running_상태가_된다() {
		// given
		Ec2Client sut = ec2Client();
		String vpcId = sut.createVpc(r -> r.cidrBlock("10.2.0.0/16")).vpc().vpcId();
		String subnetId = sut.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.2.1.0/24")
				.availabilityZone("ap-northeast-2a")).subnet().subnetId();
		String imageId = sut.describeImages(r -> r.owners("099720109477")).images().get(0).imageId();

		// when
		String instanceId = sut.runInstances(r -> r.imageId(imageId).instanceType(InstanceType.T3_MEDIUM)
				.minCount(1).maxCount(1).subnetId(subnetId)).instances().get(0).instanceId();
		try {
			sut.waiter().waitUntilInstanceRunning(r -> r.instanceIds(instanceId),
					o -> o.waitTimeout(Duration.ofMinutes(3)));

			// then
			Instance instance = sut.describeInstances(r -> r.instanceIds(instanceId))
					.reservations().get(0).instances().get(0);
			assertThat(instance).isNotNull();
			assertSoftly(softly -> {
				softly.assertThat(instance.state().name()).isEqualTo(InstanceStateName.RUNNING);
				softly.assertThat(instance.subnetId()).isEqualTo(subnetId);
				softly.assertThat(instance.instanceType()).isEqualTo(InstanceType.T3_MEDIUM);
			});
		} finally {
			sut.terminateInstances(r -> r.instanceIds(instanceId));
		}
	}
}
