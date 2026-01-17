package org.marshive.parse;

import java.util.Arrays;

/**
 * 表示一个已解析的数据帧。
 * 包含事件发生的相对时间（从会话开始计算的毫秒数）、事件类型和负载数据。
 */
public class ParsedFrame {
    /** 事件发生的相对时间（从会话开始计算的毫秒数） */
    private final long elapsedTime;
    private final byte type;
    private final byte size;
    private final byte[] payload;
    
    /**
     * 创建一个新的解析帧
     * @param elapsedTime 事件发生的相对时间（从会话开始计算的毫秒数）
     * @param type 事件类型
     * @param size 数据包大小
     * @param payload 负载数据
     */
    public ParsedFrame(long elapsedTime, byte type, byte size, byte[] payload) {
        this.elapsedTime = elapsedTime;
        this.type = type;
        this.size = size;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }
    
    /**
     * 获取事件发生的相对时间
     * @return 从会话开始计算的毫秒数
     */
    public long getElapsedTime() {
        return elapsedTime;
    }
    
    public byte getType() {
        return type;
    }
    
    public byte getSize() {
        return size;
    }
    
    public byte[] getPayload() {
        return payload.clone();
    }
    
    /**
     * 获取事件类型枚举
     * @return 事件类型枚举，如果未知类型则返回null
     */
    public EventType getEventType() {
        return EventType.fromByte(type);
    }
    
    /**
     * 获取事件名称
     * @return 事件名称字符串
     */
    public String getEventName() {
        return EventType.getNameByCode(type);
    }
    
    /**
     * 将帧转换为存储格式的字符串。
     * 格式: +时间 @事件名称 $$ type $$ size $$ payload字节...
     * @return 格式化的字符串
     */
    public String toStorageFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(elapsedTime);
        sb.append(" @").append(getEventName());
        sb.append(" $$ ").append(type & 0xFF);
        sb.append(" $$ ").append(size & 0xFF);
        for (byte b : payload) {
            sb.append(" $$ ").append(b & 0xFF);
        }
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return "ParsedFrame{" +
                "elapsedTime=" + elapsedTime +
                ", type=0x" + String.format("%02X", type) +
                ", size=" + (size & 0xFF) +
                ", eventName=" + getEventName() +
                ", payload=" + Arrays.toString(payload) +
                '}';
    }
}
