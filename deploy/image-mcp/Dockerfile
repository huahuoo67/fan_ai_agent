FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --chown=app:app app.jar /app/app.jar
USER app
EXPOSE 8127
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
