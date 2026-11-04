# Builds and runs the gateway. Tests run in CI (./mvnw verify), so the image build skips them.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
# A fixed numeric user, so Kubernetes can check it isn't root (runAsNonRoot). OpenShift runs it as its
# own random user instead, which works too: the app only reads its jar.
RUN useradd --system --no-create-home --uid 10001 gateway
WORKDIR /app
COPY --from=build /src/target/cert-gateway.jar cert-gateway.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "cert-gateway.jar"]
