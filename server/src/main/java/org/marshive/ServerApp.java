package org.marshive;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.marshive.channel.CommandInboundHandler;
import org.marshive.channel.ProtoInboundChannelHandler;
import org.marshive.channel.RequestHandler;
import org.marshive.channel.ResponseOutboundHandler;
import org.marshive.dao.ClientDAO;
import org.marshive.util.InstructionHelper;

@Slf4j
public class ServerApp {
    private static final ClientDAO CLIENT_DAO = ClientDAO.getInstance();
    
    private static final String ENVIRONMENT = System.getProperty("environment");
    
    public static void main(String[] args) {
        try {
            final int port;
            if (args == null || args.length == 0 || args[0].isEmpty()) {
                port = 9000; // 默认端口
            } else {
                port = Integer.parseInt(args[0]);
            }
            start(port);
        } catch (IllegalArgumentException e) {
            System.err.println("Usage: java -jar marshive-server.jar <port>");
            System.exit(1);
        }
    }
    
    public static void start(int port) {
        // 1. 初始化线程池
        final EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(20, NioIoHandler.newFactory());
        
        // 2. 初始化启动引导实例
        ServerBootstrap bootstrap = new ServerBootstrap()
                                    .group(bossGroup, workerGroup)
                                    .channel(NioServerSocketChannel.class)
                                    .childHandler(new ChannelInitializer<SocketChannel>() {
                                        @Override
                                        protected void initChannel(SocketChannel ch) throws Exception {
                                            // 2.1 初始化流水线
                                            ch.pipeline()
                                            .addLast(new ProtoInboundChannelHandler())
                                            .addLast(new CommandInboundHandler())
                                            .addLast(new RequestHandler())
                                            .addLast(new ResponseOutboundHandler());
                                            
                                            // 2.2 初始化完成后向 ClientManager 注册 Client
                                            // 向 clientManager 管理类递交 channel 之后，由管理类管理其生命周期
                                            CLIENT_DAO.createClient(ch);
                                            
                                            // 2.3 日志操作
                                            log.info("客户端已连接: {}", ch.remoteAddress());
                                            ch.closeFuture().addListener(future -> {
                                                log.info("客户端已断开: {}", ch.remoteAddress());
                                            });
                                        }
                                    });
        
        // 3. 绑定端口，启动服务器
        ChannelFuture bindFuture = bootstrap.bind(port);
        
        // 4. 添加监听器，处理启动结果
        bindFuture.addListener(future -> {
            if (future.isSuccess()) {
                log.info("服务器成功在端口 {} 启动", port);
                bindFuture.channel().closeFuture().addListener(f -> {
                    shutdown();
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                });
                if (!"prod".equals(ENVIRONMENT)) {
                    InstructionHelper.run(GlobalEventExecutor.INSTANCE);
                }
            } else {
                log.error("Failed to start server on port {}", port, future.cause());
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }
        });
    }
    
    public static void shutdown() {
        CLIENT_DAO.closeAllClients();
    }
}
