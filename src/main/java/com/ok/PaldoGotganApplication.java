package com.ok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaldoGotganApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaldoGotganApplication.class, args);
    }

    // Claude 리뷰 테스트용
    public static String greet(String name) {
        return "hello " + name.trim();   // null이면 NPE
    }


}
