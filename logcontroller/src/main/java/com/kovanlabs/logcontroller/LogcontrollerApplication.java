package com.kovanlabs.logcontroller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka
@EnableScheduling
@EnableMongoRepositories(basePackages = "com.kovanlabs.logcontroller.mongo.repository")
@EnableJpaRepositories(basePackages = "com.kovanlabs.logcontroller.jpa.repository")
public class LogcontrollerApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                SpringApplication.run(LogcontrollerApplication.class, args);

        Environment env = context.getEnvironment();
        System.out.println("Mongo URI = " + env.getProperty("spring.data.mongodb.uri"));
    }
}
