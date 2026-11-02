FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S secureflow && adduser -S secureflow -G secureflow
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER secureflow
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
