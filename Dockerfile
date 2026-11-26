# One Dockerfile shared by all 6 services, selected by MODULE (docker build --build-arg MODULE=OrderService .).
# Maven doesn't run inside the image: CI runs ./mvnw package first and this only COPYs the artifact. There are no RUN steps,
# so an amd64 image builds directly on an arm64 Mac (no QEMU emulation needed)
FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar
# Container memory is capped by the ECS task (1 GB): heap = 75% of container memory, the rest goes to metaspace, thread stacks and Netty direct memory;
# on OOM exit immediately and let ECS start a new task -- easier to debug than a half-dead process
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Don't run as root (the base image ships an ubuntu user, uid 1000)
USER 1000
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
