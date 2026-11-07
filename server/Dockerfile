# Build the :server distribution, then run it on a slim JRE.
# Build context is the repo root:
#   docker build -f server/Dockerfile -t hush-server .
# Deploy with ../deploy-server.sh (bind-mounts the host data dir onto /data).

FROM gradle:8.10.2-jdk17 AS build
WORKDIR /app
# AGP demands an SDK location even for a pure-JVM target; a stub path is enough because
# :server:installDist never reads it.
ENV ANDROID_HOME=/opt/android-sdk
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/server/build/install/server /app
EXPOSE 8080
# The archive DB lives under /data, which deploy-server.sh bind-mounts from the host so a
# redeploy never wipes it.
ENV HUSH_DB_PATH=/data/hush.db
# HUSH_TOKEN is passed at run time via --env-file, never baked into the image.
ENTRYPOINT ["/app/bin/server"]
