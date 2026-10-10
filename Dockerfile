# syntax=docker/dockerfile:1

FROM node:24-alpine AS frontend

WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build


FROM eclipse-temurin:25-jdk-noble AS build

ARG VERSION=0.0.1
ARG REVISION=unknown

WORKDIR /build

COPY backend/.mvn/ .mvn/
COPY --chmod=755 backend/mvnw mvnw
COPY backend/pom.xml pom.xml

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp dependency:go-offline

COPY backend/src/ src/
COPY --from=frontend /frontend/dist/ src/main/resources/static/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true -Deplsync.build.version="${VERSION}" -Deplsync.build.commit="${REVISION}" package \
    && cp target/eplsync-*.jar /build/app.jar


FROM eclipse-temurin:25-jre-noble AS runtime

ARG VERSION=0.0.1
ARG REVISION=unknown

LABEL org.opencontainers.image.title="EPL Sync" \
      org.opencontainers.image.description="Aplicación para gestionar el catálogo de ePubLibre y las descargas mediante un cliente torrent." \
      org.opencontainers.image.source="https://github.com/rlibanez/eplsync" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.authors="Roberto López @rlibanez"

ARG PUID=1000
ARG PGID=1000

RUN mkdir -p -m 700 /app/data /app/logs \
    && chown -R ${PUID}:${PGID} /app

WORKDIR /app

COPY --from=build \
    --chown=${PUID}:${PGID} \
    /build/app.jar /app/app.jar

COPY --chmod=755 scripts/eplsync-admin /app/eplsync-admin

USER ${PUID}:${PGID}

EXPOSE 8088

ENTRYPOINT ["sh", "-c", "umask 077; exec java --enable-native-access=ALL-UNNAMED -jar /app/app.jar \"$@\"", "eplsync"]
