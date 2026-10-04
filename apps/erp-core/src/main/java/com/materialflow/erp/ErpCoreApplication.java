package com.materialflow.erp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.materialflow.erp.persistence")
@SpringBootApplication
public class ErpCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpCoreApplication.class, args);
    }
}
