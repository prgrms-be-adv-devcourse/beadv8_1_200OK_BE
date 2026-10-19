package com.ok.delivery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 판매자 배송 설정의 기본값을 설정 파일에서 읽어 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DefaultShippingProperties.class)
public class ShippingSettingConfig {
}
