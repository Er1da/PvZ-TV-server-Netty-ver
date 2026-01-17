package org.marshive.parse;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

/**
 * 转发数据帧解析器。
 * 作为 Netty ChannelHandler 插入到 pipeline 中，拦截转发的数据并进行解析。
 * 
 * <p>解析过程是异步的，不会阻塞数据转发。</p>
 */
@Slf4j
public class FrameParser extends ChannelDuplexHandler {
    private final FrameBuffer frameBuffer;
    private final String direction;
    
    /**
     * 创建一个新的帧解析器
     * @param sessionId 会话标识
     * @param direction 数据方向标识（如 "host-to-guest" 或 "guest-to-host"）
     */
    public FrameParser(String sessionId, String direction) {
        this.direction = direction;
        FrameStorage storage = new FrameStorage(sessionId + "_" + direction);
        this.frameBuffer = new FrameBuffer(storage);
        log.info("帧解析器已创建, 会话: {}, 方向: {}", sessionId, direction);
    }
    
    /**
     * 创建一个默认的帧解析器（不进行存储）
     */
    public FrameParser() {
        this.direction = "unknown";
        this.frameBuffer = null;
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf && frameBuffer != null) {
            ByteBuf buf = (ByteBuf) msg;
            // 异步解析数据帧，不阻塞转发
            try {
                frameBuffer.appendAndParse(buf);
            } catch (Exception e) {
                log.error("解析帧数据失败: {}", e.getMessage());
            }
        }
        // 继续传递消息进行转发
        ctx.fireChannelRead(msg);
    }
    
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 连接关闭时释放资源
        if (frameBuffer != null) {
            frameBuffer.release();
            log.info("帧解析器资源已释放, 方向: {}", direction);
        }
        super.channelInactive(ctx);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("帧解析器异常: {}", cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }
}
