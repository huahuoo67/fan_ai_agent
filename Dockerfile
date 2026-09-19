FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src/main/java ./src/main/java
COPY src/main/resources/document ./src/main/resources/document
COPY src/main/resources/application.example.yml ./src/main/resources/application.yml
COPY src/main/resources/application-prod.yml ./src/main/resources/application-prod.yml
RUN printf '{"mcpServers":{}}\n' > src/main/resources/mcp-servers.json \
    && mvn -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/tmp && chown -R app:app /app
COPY --from=build --chown=app:app /build/target/fan-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8123
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
