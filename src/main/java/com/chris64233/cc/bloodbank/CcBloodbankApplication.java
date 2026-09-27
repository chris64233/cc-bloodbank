package com.chris64233.cc.bloodbank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CcBloodbankApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcBloodbankApplication.class, args);
    }
}
