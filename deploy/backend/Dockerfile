FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/tmp && chown -R app:app /app
COPY --chown=app:app app.jar /app/app.jar
USER app
EXPOSE 8123
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
