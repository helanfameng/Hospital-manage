package com.hospital.inpatient;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@MapperScan("com.hospital.inpatient.mapper")
@EnableMethodSecurity
@EnableScheduling
public class HospitalInpatientApplication {
    public static void main(String[] args) {
        SpringApplication.run(HospitalInpatientApplication.class, args);
    }
}

