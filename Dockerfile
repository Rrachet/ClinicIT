# ClinicIT API. Build: docker build -t clinicit-api .
# Tests are not run here; CI runs them against PostgreSQL before anything is merged.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -q package -DskipTests \
    && cp target/clinicit-*.jar /src/clinicit.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home clinicit
WORKDIR /app
COPY --from=build /src/clinicit.jar /app/clinicit.jar
USER clinicit
# 8080: public API. 8081: health probes and metrics, for the orchestrator only.
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/clinicit.jar"]
