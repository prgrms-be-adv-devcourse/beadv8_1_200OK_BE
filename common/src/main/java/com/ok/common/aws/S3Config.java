package com.ok.common.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class S3Config {
	@Bean
	S3Client s3Client(
			@Value("${app.s3.region}") String region,
			@Value("${app.s3.access-key:}") String accessKey,
			@Value("${app.s3.secret-key:}") String secretKey
	) {
		return S3Client.builder()
				.region(Region.of(region))
				.credentialsProvider(credentialsProvider(accessKey, secretKey))
				.build();
	}

	private AwsCredentialsProvider credentialsProvider(String accessKey, String secretKey) {
		if (StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey)) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
		}
		return DefaultCredentialsProvider.builder().build();
	}
}
