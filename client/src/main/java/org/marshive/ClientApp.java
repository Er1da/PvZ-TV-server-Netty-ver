package org.marshive;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.*;

// 测试类
public class ClientApp {
    public static void main(String[] args) {
        final SocketChannel channel = createChannel("localhost", 9000);
        scanInput(channel, new InputStreamReader(System.in));
    }
    
    // 构造客户端通道
    private static SocketChannel createChannel(String ip, int port) {
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
                                              System.err.print("\n接收 <<< ");
                                              while (buf.isReadable()) {
                                                  byte b = buf.readByte();
                                                  System.err.printf("%02X ", b);
                                              }
                                              System.err.println();
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
    private static void scanInput(SocketChannel channel, Reader reader) {
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
                if (origins.length == 0 || origins[0].isEmpty()) continue;
                
                // 2. 转化为byte数组
                byte[] bytes = new byte[origins.length];
                for (int i = 0; i < origins.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(origins[i], 16);
                }
                
                // 3. 发送数据
                ByteBuf byteBuf = channel.alloc().directBuffer();
                byteBuf.writeBytes(bytes);
                channel.writeAndFlush(byteBuf).sync();
            } catch (IOException e) {
                System.out.println("IO异常！");
            } catch (IllegalArgumentException e) {
                System.out.println("输入格式错误");
            } catch (Throwable e) {
                System.out.println("发生错误: " + e.getMessage());
                System.exit(1);
            }
        }
        
        try {
            channel.disconnect().sync();
        } catch (InterruptedException ignored) {
        }
    }
}
