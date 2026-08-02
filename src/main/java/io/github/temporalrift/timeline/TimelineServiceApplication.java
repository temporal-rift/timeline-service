package io.github.temporalrift.timeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TimelineServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimelineServiceApplication.class, args);
    }
}
