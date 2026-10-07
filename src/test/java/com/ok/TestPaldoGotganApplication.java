package com.ok;

import com.ok.testsupport.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

public class TestPaldoGotganApplication {

    static void main(String[] args) {
        SpringApplication.from(PaldoGotganApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
