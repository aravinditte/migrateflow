FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN ./mvnw -B -q dependency:go-offline -DskipTests || true
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system migrateflow
COPY --from=build /app/target/migrateflow-*.jar app.jar
RUN mkdir -p /var/migrateflow/uploads && chown -R migrateflow /var/migrateflow
USER migrateflow
EXPOSE 8081
ENV MIGRATEFLOW_UPLOAD_DIR=/var/migrateflow/uploads
ENTRYPOINT ["java", "-jar", "app.jar"]
