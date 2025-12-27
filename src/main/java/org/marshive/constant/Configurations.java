package org.marshive.constant;

@Deprecated
public interface Configurations {
    /* 出入站协议相关 */
    // [数据包] -> [请求类型(1字节)]+[长度字段(4字节)]+[数据内容]
    int MAX_FRAME_LENGTH = 65536;  // 帧最大长度
    int LENGTH_FIELD_OFFSET = 1;  // 长度字段偏移量
    int LENGTH_FIELD_LENGTH = 4;  // 长度字段长度
    int LENGTH_ADJUSTMENT = 0;  // 长度调整值
    int INITIAL_BYTES_TO_STRIP = 4;  // 初始跳过的字节数
    
    
}
