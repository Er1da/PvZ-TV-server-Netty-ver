import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.io.*;

public class ClientTest {
    public final static String INET_HOST = "localhost";
    public final static int INET_PORT = 9000;
    
    public final static String INPUT1 = "01 00 00 00 03 63 63 62\n" +  // 创建房间 "ccb"
                                        "02";  // 查询房间列表
    
    public final static String INPUT2 = "03 00 00 00 01\n";  // 加入房间 id=1
    
    @Test
    public void client1() {
        final SocketChannel channel = createChannel(INET_HOST, INET_PORT);
        scanInput(channel, new StringReader(INPUT1));
//        scanInput(channel, new InputStreamReader(System.in));
    }
    
    @Test
    public void client2() {
        final SocketChannel channel = createChannel(INET_HOST, INET_PORT);
        scanInput(channel, new StringReader(INPUT2));
    }
    
    // 构造客户端通道
    private SocketChannel createChannel(String ip, int port) {
        final EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        Bootstrap bootstrap = new Bootstrap()
                              .group(group)
                              .channel(NioSocketChannel.class)
                              .handler(new ChannelInitializer<SocketChannel>() {
                                  @Override
                                  protected void initChannel(SocketChannel ch) throws Exception {
                                      ch.pipeline()
                                      .addLast(new ChannelInboundHandlerAdapter() {
                                          @Override
                                          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                              // 处理服务器响应
                                              ByteBuf buf = (ByteBuf) msg;
                                              System.out.print("接收 <<< ");
                                              while (buf.isReadable()) {
                                                  byte b = buf.readByte();
                                                  System.out.printf("0x%02X ", b);
                                              }
                                              System.out.println();
                                          }
                                      })
                                      .addLast(new ChannelOutboundHandlerAdapter() {
                                          @Override
                                          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                                              ByteBuf byteBuf = ctx.alloc().directBuffer();
                                              byteBuf.readBytes((byte[]) msg);
                                              ctx.write(byteBuf, promise);
                                          }
                                      });
                                  }
                              });
        try {
            Channel channel = bootstrap.connect(ip, port).sync().channel();
            channel.closeFuture().addListener(f -> group.shutdownGracefully());
            return (SocketChannel) channel;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
    
    // 模拟输入: "0x11"或"11" -> byte 0x11
    private void scanInput(SocketChannel channel, Reader reader) {
        final BufferedReader br = new BufferedReader(reader);
        while (true) {
            try {
                // 1. 获取输入
                System.out.print("发送 >>> ");
                String line = br.readLine();
                if (line == null) {
                    System.out.println("输入结束");
                    break;
                }
                String[] origins = line.trim().split(" ");
                if (origins.length == 0) continue;
                
                // 2. 转化为byte数组
                byte[] bytes = new byte[origins.length];
                for (int i = 0; i < origins.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(origins[i], 16);
                }
                
                // 3. 发送数据
                channel.writeAndFlush(bytes);
            } catch (IOException e) {
                System.out.println("IO异常！");
            } catch (IllegalArgumentException e) {
                System.out.println("输入格式错误");
            }
        }
        
        try {
            channel.disconnect().sync();
        } catch (InterruptedException ignored) {
        }
    }
}
