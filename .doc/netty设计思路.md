# Netty框架迁移

## 设计思路

主要就是拆分**ClientHandler**类，按功能点拆成数据解析、数据处理、数据响应三块。其中数据解析和数据响应分别对应两个pojo：RequestBody和ResponseBody，代码如下。接下来的处理和响应都是借助这两个数据类进行的

- **RequestBody**

  ```java
  public class RequestBody<T> {
      private RequestType type;
      private T payload;
  }
  ```

- **ResponseBody**

  ```java
  public class ResponseBody<T> {
      private ResponseType type;
      private T data;
  }
  ```

按照Netty的数据传输模型，数据入站时先依次经过**ProtoInboundChannelHandler**、**CommandInboundHandler**、**RequestHandler**，数据出站时经过**ResponseOutboundHandler**，入站和出站相比原框架实现了异步处理。下面简短讲一下handler的作用

- **ProtoInboundChannelHandler**——自定义协议头，可以设计魔数、乱数、校验码、帧序号、帧长度等乱七八糟的东西。由于原框架不涉及这类操作，所以此类只是简单地把数据向下传递
- **CommandInboundHandler**——将字节流转化为RequestBody对象并向下传递。此类会简单的进行一些安全处理
- **RequestHandler**——处理请求并尝试通过Client对象返回数据。大部分只做操作合法性校验，核心处理逻辑仍交给Client类处理
- **ResponseOutboundHandler**——将ResponseBody对象转化成字节流

handler只是做了解析和响应这两步，而数据处理主要还是交给Client类和数据工具类。相比ClientHandler来讲，Client其实就是把数据解析和数据响应拆出去后的ClientHandler

Socket、Client、Room的对应关系如下：

```
Socket --1-1-- Client --n-1-- Room
```



## 问题

1. 原框架进行房间查询时未限长，可能导致数据溢出或截断

   ```java
   case QUERY: {
       byte[] payload = buildRoomListPayload(rm);  // payload: [count:1] + count*([roomId:4][flags:1][nameLen:1][nameBytes])
       Proto.sendFrame(out, RespType.ROOM_LIST.code, payload);  // 长度字段仅有1字节也就是255，而房间名未限长
       break;
   }
   ```

   

   

   

   



