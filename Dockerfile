# syntax=docker/dockerfile:1
FROM maven:4.0.0-rc-7-eclipse-temurin-26 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
RUN --mount=type=secret,id=github_token,env=GITHUB_TOKEN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    mvn dependency:go-offline -q -Dspotless.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true
COPY src ./src
RUN --mount=type=secret,id=github_token,env=GITHUB_TOKEN --mount=type=secret,id=github_actor,env=GITHUB_ACTOR \
    mvn package -DskipTests -q -Dspotless.skip=true -Dcheckstyle.skip=true

FROM eclipse-temurin:26
WORKDIR /app
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --no-create-home app
COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
EXPOSE 8080
USER app
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
