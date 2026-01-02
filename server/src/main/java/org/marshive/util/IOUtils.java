package org.marshive.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.experimental.UtilityClass;

@UtilityClass
public class IOUtils {
    public void writeIntBE(ByteBuf buf, int value) {
        buf.writeInt(value);
    }
    
    /**
     * 在两个 Channel 之间进行数据转发，直到任意一方关闭连接。
     * 请注意：将 channel 交给此方法管理后，调用方不应再对 channel 进行读写操作，同时放弃对生命周期的管理
     */
    public Future<?> relay(Channel a, Channel b) {
        // 1. 构造 future 对象
        DefaultPromise<?> closeFuture = new DefaultPromise<>(GlobalEventExecutor.INSTANCE);
        
        // 2. 创建转发处理器
        ChannelInboundHandler aToB = createRelayHandler(b);
        ChannelInboundHandler bToA = createRelayHandler(a);
        
        // 3. 添加到 pipeline 首部进行转发
        a.pipeline().addFirst("relay-" + a.id() + "-to-" + b.id(), aToB);
        b.pipeline().addFirst("relay-" + b.id() + "-to-" + a.id(), bToA);
        
        // 4. 添加结束动作，当任意一方结束传输数据时，移除转发处理器并完成 future
        a.closeFuture().addListener(future -> trySetSuccess(closeFuture));
        b.closeFuture().addListener(future -> trySetSuccess(closeFuture));
        
        // 5. 返回关闭 future
        return closeFuture;
    }
    
    private ChannelInboundHandler createRelayHandler(Channel toChannel) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (toChannel.isActive()) {
                    toChannel.writeAndFlush(ReferenceCountUtil.retain(msg)).addListener(future -> {
                        if (!future.isSuccess()) {
                            ReferenceCountUtil.release(msg);
                        }
                    });
                } else {
                    ReferenceCountUtil.release(msg);
                }
            }
            
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                try {
                    if (toChannel.isActive()) toChannel.close();
                } finally {
                    super.channelInactive(ctx);
                }
            }
            
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                try {
                    if (toChannel.isActive()) toChannel.close();
                } finally {
                    ctx.close();
                }
            }
        };
    }
    
    private void trySetSuccess(DefaultPromise<?> promise) {
        if (!promise.isDone()) {
            promise.setSuccess(null);
        }
    }
}
