# G-上：6 个服务共用一个 Dockerfile，用 MODULE 区分（docker build --build-arg MODULE=OrderService .）。
# 不在镜像里跑 Maven：CI 先 ./mvnw package，这里只 COPY 产物。镜像里没有任何 RUN 步骤，
# 所以在 arm64 的 Mac 上也能直接构建 amd64 镜像（不需要 QEMU 模拟执行）
FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar
# 容器内存由 ECS task 限定（1GB）：堆按容器内存的 75% 算，剩下留给元空间、线程栈、Netty 直接内存；
# OOM 直接退出让 ECS 拉起新 task，比"半死不活"的进程好排查
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 不用 root 跑（镜像自带 ubuntu 用户，uid 1000）
USER 1000
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
