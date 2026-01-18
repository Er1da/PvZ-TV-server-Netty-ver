# 构建Docker镜像

## 准备

- 文件
  - Dockerfile
  - server.jar
- 操作系统：Ubuntu 22+
- Docker版本：29.1.2

## 步骤

1. 使Dockerfile和jar包在同一目录下

2. 运行以下命令构建镜像

   ```bash
   docker build -t pvz-server:latest .
   ```

3. 运行以下命令创建容器

   ```bash
   docker run -d \
     -p 9000:9000 \
     -v $(pwd)/logs:/app/log \
     -v $(pwd)/frames:/app/frames \
     --name pvz-server \
     pvz-server:latest
   ```