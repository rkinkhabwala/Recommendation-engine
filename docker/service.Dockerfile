# syntax=docker/dockerfile:1.7
# One Dockerfile for every service: the build stage compiles the whole repo once (BuildKit caches
# it across services); the runtime stage copies one service's boot jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q bootJar :tools:event-simulator:installDist -x test

FROM eclipse-temurin:21-jre AS service
ARG SERVICE
RUN useradd --system --uid 10001 app && mkdir -p /app /tmp/recs-streams && chown app /tmp/recs-streams
COPY --from=build /src/services/${SERVICE}/build/libs/ /app/
USER app
# Heap at 60% of the container limit; the rest covers metaspace, threads and direct buffers
# (gRPC/netty). G1 suits these small heaps better than ZGC. Exit on OOM so the container restarts.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:MaxDirectMemorySize=96m -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/$(ls /app | head -1)"]

FROM eclipse-temurin:21-jre AS simulator
COPY --from=build /src/tools/event-simulator/build/install/event-simulator/ /opt/event-simulator/
ENTRYPOINT ["/opt/event-simulator/bin/event-simulator"]
