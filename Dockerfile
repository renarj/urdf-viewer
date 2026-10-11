# syntax=docker/dockerfile:1

FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system --gid 10001 urdfviewer \
    && useradd --system --uid 10001 --gid urdfviewer --home-dir /app urdfviewer \
    && mkdir -p /data/urdf-viewer \
    && chown -R urdfviewer:urdfviewer /app /data/urdf-viewer

COPY --from=build --chown=urdfviewer:urdfviewer /workspace/target/urdf-viewer-*.jar /app/urdf-viewer.jar

USER urdfviewer
ENV URDF_VIEWER_STORAGE_DIR=/data/urdf-viewer
EXPOSE 8081
VOLUME ["/data/urdf-viewer"]

ENTRYPOINT ["java", "-jar", "/app/urdf-viewer.jar"]
