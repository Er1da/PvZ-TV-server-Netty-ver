package org.marshive.parse;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 用于缓存和管理数据帧的缓冲区。
 * <ul>
 *     <li>拷贝来自 {@link FrameParser} 的数据，并提供监听此缓冲区的接口</li>
 *     <li>追加数据和解析数据为异步操作</li>
 *     <li>支持通过 {@link FrameParsingListener} 监听器异步通知解析结果</li>
 * </ul>
 * 
 * 数据帧格式: [type:1][size:1][payload:0~n]
 */
@Slf4j
public class FrameBuffer {
    private static final int HEADER_SIZE = 2; // type(1) + size(1)
    
    private final ByteBuf buffer;
    private final FrameStorage storage;
    private final long startTime;
    private final List<FrameParsingListener> parsingListeners = new CopyOnWriteArrayList<>();
    private volatile boolean notifyIndividualFrames = true;
    
    /**
     * 创建一个新的帧缓冲区
     * @param storage 帧存储实例
     */
    public FrameBuffer(FrameStorage storage) {
        this.buffer = Unpooled.buffer(1024);
        this.storage = storage;
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * 向缓冲区追加数据
     * @param data 要追加的数据
     */
    public synchronized void append(ByteBuf data) {
        if (data == null || !data.isReadable()) {
            return;
        }
        buffer.writeBytes(data.duplicate());
    }
    
    /**
     * 解析缓冲区中的数据帧
     * @return 解析出的帧列表
     */
    public synchronized List<ParsedFrame> parseFrames() {
        List<ParsedFrame> frames = new ArrayList<>();
        
        while (buffer.readableBytes() >= HEADER_SIZE) {
            buffer.markReaderIndex();
            
            byte type = buffer.readByte();
            byte size = buffer.readByte();
            int packetSize = size & 0xFF;
            
            // 检查数据包大小是否合理
            if (packetSize < HEADER_SIZE) {
                log.warn("无效的数据包大小: {}, 丢弃该字节", packetSize);
                // 跳过这个无效的数据包，避免死循环
                break;
            }
            
            int payloadSize = packetSize - HEADER_SIZE;
            
            // 检查是否有足够的负载数据
            if (buffer.readableBytes() < payloadSize) {
                // 数据不完整，重置读索引等待更多数据
                buffer.resetReaderIndex();
                break;
            }
            
            // 读取负载数据
            byte[] payload = new byte[payloadSize];
            if (payloadSize > 0) {
                buffer.readBytes(payload);
            }
            
            // 创建解析后的帧
            long timestamp = System.currentTimeMillis() - startTime;
            ParsedFrame frame = new ParsedFrame(timestamp, type, size, payload);
            frames.add(frame);
            
            // 存储帧数据
            if (storage != null && !storage.isClosed()) {
                storage.store(frame);
            }
            
            // 异步通知所有解析监听器（如果启用单帧通知）
            if (notifyIndividualFrames) {
                notifyFrameParsed(frame);
            }
            
            log.debug("解析到帧: {}", frame);
        }
        
        // 丢弃已读取的数据
        buffer.discardReadBytes();
        
        // 通知批量解析完成
        if (!frames.isEmpty()) {
            notifyFramesParsed(frames);
        }
        
        return frames;
    }
    
    /**
     * 追加数据并立即解析
     * @param data 要追加的数据
     * @return 解析出的帧列表
     */
    public List<ParsedFrame> appendAndParse(ByteBuf data) {
        append(data);
        return parseFrames();
    }
    
    /**
     * 获取缓冲区中待处理的字节数
     * @return 字节数
     */
    public int pendingBytes() {
        return buffer.readableBytes();
    }
    
    /**
     * 清空缓冲区
     */
    public synchronized void clear() {
        buffer.clear();
    }
    
    /**
     * 释放缓冲区资源
     */
    public synchronized void release() {
        if (buffer.refCnt() > 0) {
            buffer.release();
        }
        if (storage != null) {
            storage.close();
        }
        // 通知所有监听器解析已关闭
        notifyParsingClosed();
    }
    
    /**
     * 添加解析监听器
     * @param listener 解析监听器
     */
    public void addParsingListener(FrameParsingListener listener) {
        if (listener != null) {
            parsingListeners.add(listener);
        }
    }
    
    /**
     * 移除解析监听器
     * @param listener 要移除的监听器
     */
    public void removeParsingListener(FrameParsingListener listener) {
        parsingListeners.remove(listener);
    }
    
    /**
     * 移除所有解析监听器
     */
    public void clearParsingListeners() {
        parsingListeners.clear();
    }
    
    /**
     * 获取当前注册的解析监听器数量
     * @return 监听器数量
     */
    public int getParsingListenerCount() {
        return parsingListeners.size();
    }
    
    /**
     * 设置是否通知单个帧解析事件。
     * 禁用单帧通知可以提高批量处理性能，此时只会触发批量解析完成通知。
     * 
     * @param notifyIndividualFrames 是否通知单帧解析事件，默认为 true
     */
    public void setNotifyIndividualFrames(boolean notifyIndividualFrames) {
        this.notifyIndividualFrames = notifyIndividualFrames;
    }
    
    /**
     * 检查是否通知单个帧解析事件
     * @return 是否通知单帧解析事件
     */
    public boolean isNotifyIndividualFrames() {
        return notifyIndividualFrames;
    }
    
    /**
     * 通知所有监听器单个帧已解析
     * @param frame 解析完成的帧
     */
    private void notifyFrameParsed(ParsedFrame frame) {
        for (FrameParsingListener listener : parsingListeners) {
            try {
                listener.onFrameParsed(frame);
            } catch (Exception e) {
                log.error("通知解析监听器失败: {}", e.getMessage());
                // 不调用 notifyParsingError 避免递归风险
            }
        }
    }
    
    /**
     * 通知所有监听器批量帧已解析
     * @param frames 解析完成的帧列表
     */
    private void notifyFramesParsed(List<ParsedFrame> frames) {
        for (FrameParsingListener listener : parsingListeners) {
            try {
                listener.onFramesParsed(frames);
            } catch (Exception e) {
                log.error("通知批量解析监听器失败: {}", e.getMessage());
                // 不调用 notifyParsingError 避免递归风险
            }
        }
    }
    
    /**
     * 通知所有监听器解析发生错误
     * @param cause 异常原因
     */
    private void notifyParsingError(Throwable cause) {
        for (FrameParsingListener listener : parsingListeners) {
            try {
                listener.onParsingError(cause);
            } catch (Exception e) {
                log.error("通知解析错误监听器失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 通知所有监听器解析已关闭
     */
    private void notifyParsingClosed() {
        for (FrameParsingListener listener : parsingListeners) {
            try {
                listener.onParsingClosed();
            } catch (Exception e) {
                log.error("通知解析关闭监听器失败: {}", e.getMessage());
            }
        }
    }
}
