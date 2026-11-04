# Builds and runs the gateway. Tests run in CI (./mvnw verify), so the image build skips them.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home gateway
WORKDIR /app
COPY --from=build /src/target/cert-gateway.jar cert-gateway.jar
USER gateway
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "cert-gateway.jar"]
