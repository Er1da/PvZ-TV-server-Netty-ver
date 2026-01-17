package org.marshive.parse;

import java.util.Arrays;

/**
 * 表示一个已解析的数据帧。
 * 包含事件发生时间戳、事件类型和负载数据。
 */
public class ParsedFrame {
    private final long timestamp;
    private final byte type;
    private final byte size;
    private final byte[] payload;
    
    public ParsedFrame(long timestamp, byte type, byte size, byte[] payload) {
        this.timestamp = timestamp;
        this.type = type;
        this.size = size;
        this.payload = payload != null ? payload.clone() : new byte[0];
    }
    
    public long getTimestamp() {
        return timestamp;
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
     * 格式: +时间戳 @事件名称 $$ type $$ size $$ payload字节...
     * @return 格式化的字符串
     */
    public String toStorageFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(timestamp);
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
                "timestamp=" + timestamp +
                ", type=0x" + String.format("%02X", type) +
                ", size=" + (size & 0xFF) +
                ", eventName=" + getEventName() +
                ", payload=" + Arrays.toString(payload) +
                '}';
    }
}
