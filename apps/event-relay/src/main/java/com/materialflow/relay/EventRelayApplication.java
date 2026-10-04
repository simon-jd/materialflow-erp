package com.materialflow.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(com.materialflow.relay.outbox.RelayProperties.class)
public class EventRelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventRelayApplication.class, args);
    }
}
