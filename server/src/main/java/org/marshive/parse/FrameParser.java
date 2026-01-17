package org.marshive.parse;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 转发数据帧解析器。
 * 作为 Netty ChannelHandler 插入到 pipeline 中，拦截转发的数据并进行解析。
 * 
 * <p>解析过程是异步的，不会阻塞数据转发。</p>
 * <p>支持通过 {@link FrameInterceptionListener} 监听器异步通知拦截事件，
 * 以及通过 {@link FrameParsingListener} 监听器异步通知解析结果。</p>
 */
@Slf4j
public class FrameParser extends ChannelDuplexHandler {
    private final FrameBuffer frameBuffer;
    private final String direction;
    private final List<FrameInterceptionListener> interceptionListeners = new CopyOnWriteArrayList<>();
    
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
            
            // 异步通知所有拦截监听器
            notifyDataIntercepted(buf);
            
            // 异步解析数据帧，不阻塞转发
            try {
                frameBuffer.appendAndParse(buf);
            } catch (Exception e) {
                log.error("解析帧数据失败: {}", e.getMessage());
                notifyInterceptionError(e);
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
        // 通知所有拦截监听器已关闭
        notifyInterceptionClosed();
        super.channelInactive(ctx);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("帧解析器异常: {}", cause.getMessage());
        notifyInterceptionError(cause);
        super.exceptionCaught(ctx, cause);
    }
    
    /**
     * 添加拦截监听器
     * @param listener 拦截监听器
     */
    public void addInterceptionListener(FrameInterceptionListener listener) {
        if (listener != null) {
            interceptionListeners.add(listener);
        }
    }
    
    /**
     * 移除拦截监听器
     * @param listener 要移除的监听器
     */
    public void removeInterceptionListener(FrameInterceptionListener listener) {
        interceptionListeners.remove(listener);
    }
    
    /**
     * 移除所有拦截监听器
     */
    public void clearInterceptionListeners() {
        interceptionListeners.clear();
    }
    
    /**
     * 获取当前注册的拦截监听器数量
     * @return 监听器数量
     */
    public int getInterceptionListenerCount() {
        return interceptionListeners.size();
    }
    
    /**
     * 添加解析监听器（代理到 FrameBuffer）
     * @param listener 解析监听器
     */
    public void addParsingListener(FrameParsingListener listener) {
        if (frameBuffer != null && listener != null) {
            frameBuffer.addParsingListener(listener);
        }
    }
    
    /**
     * 移除解析监听器（代理到 FrameBuffer）
     * @param listener 要移除的监听器
     */
    public void removeParsingListener(FrameParsingListener listener) {
        if (frameBuffer != null) {
            frameBuffer.removeParsingListener(listener);
        }
    }
    
    /**
     * 获取数据方向标识
     * @return 数据方向标识
     */
    public String getDirection() {
        return direction;
    }
    
    /**
     * 获取帧缓冲区
     * @return 帧缓冲区，如果不进行存储则返回 null
     */
    public FrameBuffer getFrameBuffer() {
        return frameBuffer;
    }
    
    /**
     * 通知所有拦截监听器数据已被拦截
     * @param data 被拦截的数据
     */
    private void notifyDataIntercepted(ByteBuf data) {
        if (interceptionListeners.isEmpty()) {
            return;
        }
        // 创建只读副本以保护原始数据
        ByteBuf dataCopy = data.duplicate().asReadOnly();
        for (FrameInterceptionListener listener : interceptionListeners) {
            try {
                listener.onDataIntercepted(dataCopy, direction);
            } catch (Exception e) {
                log.error("通知拦截监听器失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 通知所有拦截监听器发生错误
     * @param cause 异常原因
     */
    private void notifyInterceptionError(Throwable cause) {
        for (FrameInterceptionListener listener : interceptionListeners) {
            try {
                listener.onInterceptionError(cause, direction);
            } catch (Exception e) {
                log.error("通知拦截错误监听器失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 通知所有拦截监听器已关闭
     */
    private void notifyInterceptionClosed() {
        for (FrameInterceptionListener listener : interceptionListeners) {
            try {
                listener.onInterceptionClosed(direction);
            } catch (Exception e) {
                log.error("通知拦截关闭监听器失败: {}", e.getMessage());
            }
        }
    }
}
