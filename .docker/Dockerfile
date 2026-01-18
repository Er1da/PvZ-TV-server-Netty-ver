# Dockerfile
FROM openjdk:8-jre-alpine

# 创建工作目录
WORKDIR /app

# 复制 server.jar 到容器中
COPY server.jar /app/server.jar

# 创建日志目录（如果不存在）
RUN mkdir -p /app/log
RUN mkdir -p /app/frames

# 暴露 9000 端口（固定）
EXPOSE 9000

# 启动命令 - 固定端口 9000，使用 prod 环境
ENTRYPOINT ["java", "-Denvironment=prod", "-jar", "server.jar"]
