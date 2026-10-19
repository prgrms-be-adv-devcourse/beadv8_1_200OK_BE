package com.ok.delivery.config;

import com.ok.delivery.domain.BusinessDayCalendar;
import com.ok.delivery.domain.DeliveryDatePolicy;
import com.ok.delivery.domain.WeekendBusinessDayCalendar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 영업일 계산기와 도착 예정일 계산을 빈으로 등록한다.
 * 도메인 클래스에 스프링 의존을 두지 않으려고 설정에서 등록한다. 공휴일을 반영할 때는 캘린더 구현체만 바꾼다.
 */
@Configuration(proxyBeanMethods = false)
public class DeliveryDateConfig {

    @Bean
    public BusinessDayCalendar businessDayCalendar() {
        return new WeekendBusinessDayCalendar();
    }

    @Bean
    public DeliveryDatePolicy deliveryDatePolicy(BusinessDayCalendar businessDayCalendar) {
        return new DeliveryDatePolicy(businessDayCalendar);
    }
}
