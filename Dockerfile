# ClinicIT API. Build: docker build -t clinicit-api .
# Tests are not run here; CI runs them against PostgreSQL before anything is merged.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
# Extracted layout (application jar + lib/): faster to start than the nested fat jar,
# which matters on small CPU allowances such as a free 0.1 vCPU instance.
RUN mvn -B -ntp -q package -DskipTests \
    && cp target/clinicit-*.jar /src/clinicit.jar \
    && java -Djarmode=tools -jar /src/clinicit.jar extract --destination /src/app

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home clinicit
WORKDIR /app
COPY --from=build /src/app /app
USER clinicit
# 8080: public API. 8081: health probes and metrics, for the orchestrator only.
EXPOSE 8080 8081
# Startup is CPU-bound (class loading and JIT). C1-only compilation and the serial collector
# cut a cold start on 0.1 vCPU / 512 MB from about 410 s to about 175 s, measured with
# cgroup limits; MaxRAMPercentage leaves room for metaspace and thread stacks.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "/app/clinicit.jar"]
