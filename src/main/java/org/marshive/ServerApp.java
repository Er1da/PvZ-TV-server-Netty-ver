package org.marshive;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.marshive.channel.CommandInboundHandler;
import org.marshive.channel.ProtoInboundChannelHandler;
import org.marshive.channel.RequestHandler;
import org.marshive.channel.ResponseOutboundHandler;
import org.marshive.util.ClientManager;

@Slf4j
public class ServerApp {
    private static final ClientManager clientManager = ClientManager.getInstance();
    
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException();
            int port = Integer.parseInt(args[0]);
            start(port);
        } catch (IllegalArgumentException e) {
            System.err.println("Usage: java -jar marshive-server.jar <port>");
            System.exit(1);
        }
    }
    
    public static void start(int port) {
        final EventLoopGroup bossGroup = new NioEventLoopGroup();
        final EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        ServerBootstrap bootstrap = new ServerBootstrap()
                                    .group(bossGroup, workerGroup)
                                    .channel(NioServerSocketChannel.class)
                                    .childHandler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel ch) throws Exception {
                                            ch.pipeline()
                                            .addLast(new ProtoInboundChannelHandler())
                                            .addLast(new CommandInboundHandler())
                                            .addLast(new RequestHandler())
                                            .addLast(new ResponseOutboundHandler());
                                            
                                            // 初始化完成后向 ClientManager 注册 Client
                                            clientManager.createClient(ch);
                                            
                                            log.info("客户端已连接: {}", ch.remoteAddress());
                                        }
                                    });
        ChannelFuture bindFuture = bootstrap.bind(port);
        bindFuture.addListener(future -> {
            if (future.isSuccess()) {
                log.info("服务器成功在端口 {} 启动", port);
                bindFuture.channel().closeFuture().addListener(f -> {
                    shutdown();
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                });
            } else {
                log.error("Failed to start server on port {}", port, future.cause());
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        });
        
    }
    
    public static void shutdown() {
        clientManager.closeAllClients();
    }
}
