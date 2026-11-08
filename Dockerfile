# The test target is used by Cloud Build; it is not part of the runtime image.
FROM maven:3.9.11-eclipse-temurin-21 AS backend-deps
WORKDIR /build/backend
COPY backend/pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline

FROM backend-deps AS backend-tests
USER root
RUN apt-get update && apt-get install -y --no-install-recommends postgresql && rm -rf /var/lib/apt/lists/*
COPY backend/src ./src
COPY scripts/test-backend-linux.sh /build/scripts/test-backend-linux.sh
RUN bash /build/scripts/test-backend-linux.sh

FROM node:24-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run lint && npm run build

FROM backend-deps AS backend-build
COPY backend/src ./src
COPY --from=frontend-build /build/frontend/dist/ ./src/main/resources/static/
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0" TZ=Australia/Hobart
RUN groupadd --gid 10001 rwc && useradd --uid 10001 --gid rwc --no-create-home --shell /usr/sbin/nologin rwc
COPY --from=backend-build --chown=rwc:rwc /build/backend/target/waste-tracking-api-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
