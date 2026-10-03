# Builds either module of the multi-module project: --build-arg MODULE=core-banking | external-bank
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY core-banking/pom.xml core-banking/
COPY external-bank/pom.xml external-bank/
ARG MODULE
# Dependency layer is cached until a pom changes
RUN mvn -B -q -pl ${MODULE} dependency:go-offline || true
COPY core-banking/src core-banking/src
COPY external-bank/src external-bank/src
RUN mvn -B -q -pl ${MODULE} package -DskipTests

FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
