package com.materialflow.projection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafkaStreams;

@EnableKafkaStreams
@SpringBootApplication
public class StreamProjectionApplication {
    public static void main(String[] args) {
        SpringApplication.run(StreamProjectionApplication.class, args);
    }
}
