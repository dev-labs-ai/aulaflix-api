# The API's image. It runs the API, or, with `admin` as its first argument, the admin command:
#   docker compose run --rm api admin create --email … --name …
#
# Hand-written rather than ./mvnw spring-boot:build-image: the buildpacks' memory calculator sets -Xmx and friends
# itself, while the container topology sizes the heap with -XX:MaxRAMPercentage=75 against the container's mem_limit.
# Secrets are never part of the image: they arrive at run time as files under /run/secrets/.

# The build: the JDK and Maven, which never reach the runtime image
FROM eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24 AS build
WORKDIR /build

# The dependencies first, in a layer of their own, so that a source-only change reuses it. go-offline misses a few
# plugin dependencies, so the package step below keeps network access.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw --batch-mode --quiet dependency:go-offline

# The tests run in CI and on the developer's machine, with Docker for their Testcontainers; not here
COPY src src
RUN ./mvnw --batch-mode --quiet -DskipTests package \
    && java -Djarmode=tools -jar target/aulaflix-api-*.jar extract --layers --destination extracted \
    && mv extracted/application/aulaflix-api-*.jar extracted/application/aulaflix-api.jar

# The runtime: a JRE, the extracted jar's layers, least to most often changed, and an unprivileged user
FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24
RUN addgroup -S -g 10001 aulaflix && adduser -S -u 10001 -G aulaflix -H -h /app aulaflix
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001

EXPOSE 8080
# Exec form, so the JVM is PID 1 and receives SIGTERM, which Spring Boot's graceful shutdown handles. The heap is
# sized against the container's memory limit. The arguments given to `docker run`/`compose run` follow the jar's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "aulaflix-api.jar"]
