package org.marshive.parse;

import io.netty.buffer.ByteBuf;

/**
 * 帧数据拦截监听器接口。
 * 用于在 {@link FrameParser} 拦截到原始数据时进行异步回调通知。
 * 
 * <p>实现此接口可以在数据被拦截时执行自定义逻辑，如日志记录、统计等。</p>
 */
public interface FrameInterceptionListener {
    
    /**
     * 当数据被拦截时调用。
     * 此方法在 Netty 的 IO 线程中异步执行，不应执行耗时操作。
     * 
     * @param data 被拦截的原始数据（只读副本）
     * @param direction 数据方向标识（如 "host-to-guest" 或 "guest-to-host"）
     */
    void onDataIntercepted(ByteBuf data, String direction);
    
    /**
     * 当拦截器发生异常时调用。
     * 
     * @param cause 异常原因
     * @param direction 数据方向标识
     */
    default void onInterceptionError(Throwable cause, String direction) {
        // 默认空实现，子类可选择覆盖
    }
    
    /**
     * 当拦截器关闭时调用。
     * 可用于释放监听器持有的资源。
     * 
     * @param direction 数据方向标识
     */
    default void onInterceptionClosed(String direction) {
        // 默认空实现，子类可选择覆盖
    }
}
