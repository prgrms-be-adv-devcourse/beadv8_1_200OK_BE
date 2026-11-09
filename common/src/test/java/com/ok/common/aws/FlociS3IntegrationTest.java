package com.ok.common.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

@Testcontainers
class FlociS3IntegrationTest {

	@Container
	static FlociContainer floci = new FlociContainer().withRegion("ap-northeast-2");

	private S3Client s3Client() {
		return new S3Config().s3Client(floci.getRegion(), floci.getAccessKey(), floci.getSecretKey(), floci.getEndpoint());
	}

	@Test
	void 버킷을_만들고_객체를_올리고_읽는다() {
		// given
		S3Client sut = s3Client();
		sut.createBucket(r -> r.bucket("poc-media"));

		// when
		sut.putObject(r -> r.bucket("poc-media").key("products/a.txt"), RequestBody.fromString("hello"));

		// then
		assertThat(sut.getObjectAsBytes(r -> r.bucket("poc-media").key("products/a.txt")).asUtf8String())
				.isEqualTo("hello");
		assertThat(sut.listObjectsV2(r -> r.bucket("poc-media").prefix("products/")).contents())
				.extracting(S3Object::key)
				.containsExactly("products/a.txt");
	}

	@Test
	void 없는_객체를_읽으면_NoSuchKey_예외가_난다() {
		// given
		S3Client sut = s3Client();
		sut.createBucket(r -> r.bucket("poc-empty"));

		// when & then
		assertThatThrownBy(() -> sut.getObjectAsBytes(r -> r.bucket("poc-empty").key("none.txt")))
				.isInstanceOf(NoSuchKeyException.class);
	}
}
