package io.github.temporalrift.timeline;

import org.springframework.boot.SpringApplication;

public class TestTimelineServiceApplication {

    static void main(String[] args) {
        SpringApplication.from(TimelineServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
