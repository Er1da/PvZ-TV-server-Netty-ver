# PvZ-TV-server

植物大战僵尸TV触控版联机服务器

源项目：[MarsHive/PvZ-TV-server: 植物大战僵尸TV触控版联机服务器](https://github.com/MarsHive/PvZ-TV-server)

## 项目结构

```
PvZ-TV-server
 |--pom.xml
 |--client/  # 用于模拟输入的测试客户端
 |--server-old/  # 源项目
 |--server/  # 使用netty重写
     |--pom.xml
     |--src/main/
         |--resources/
         |   |--logback.xml  # 日志
         |--java/
             |--org/marshive/
                 |--ServerApp.java
                 |--channel/  # 原始io数据处理
                 |--constant/
                 |--domain/  # 核心逻辑数据
                 |--util/
```

## 构建

使用mvn构建server子模块

```
mvn -pl server package
```

得到文件`server/target/server-1.0-SNAPSHOT-jar-with-dependencies.jar`即构建完成

## 运行

开发环境（控制台输出日志）

```
java -jar server-1.0-SNAPSHOT-jar-with-dependencies.jar
```

生产环境（文件输出日志）

```
java -Denvironment=prod -jar server-1.0-SNAPSHOT-jar-with-dependencies.jar
```

端口默认为9000，如果想要更换端口可以将新端口作为程序参数启动。例如以下指令使程序监听8080端口

```
java -jar server-1.0-SNAPSHOT-jar-with-dependencies.jar 8080
```

