# Контекст сборки — корень репозитория backend (см. deploy/docker-compose.yml).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# gradlew может прийти с CRLF (Windows, autocrlf)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --version
COPY src src
COPY proto proto
COPY api api
RUN ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
COPY --from=build /app.jar /app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
