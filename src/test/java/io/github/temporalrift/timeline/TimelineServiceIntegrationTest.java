package io.github.temporalrift.timeline;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Single entry point for every integration test in this service. Spring caches a context per distinct
 * combination of these annotations, and each new context boots the whole application, so every IT declaring
 * its own variant costs a full boot. Add shared test infrastructure here rather than importing it from an
 * individual test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TimelineEventsTestCollector.class, GameEventsTestPublisher.class})
public @interface TimelineServiceIntegrationTest {}
