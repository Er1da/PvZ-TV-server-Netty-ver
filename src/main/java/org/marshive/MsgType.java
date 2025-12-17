package org.marshive;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Qhbee
 */
@Getter
public enum MsgType {
    // 定义协议类型与对应的字节码
    CREATE((byte) 0x01),
    QUERY((byte) 0x02),
    JOIN((byte) 0x03),
    LEAVE((byte) 0x04),
    START((byte) 0x05);

    private final byte code;

    // 缓存查找表，避免每次遍历，提高性能
    private static final Map<Byte, MsgType> MAP = new HashMap<>();

    static {
        for (MsgType type : values()) {
            MAP.put(type.code, type);
        }
    }

    MsgType(byte code) {
        this.code = code;
    }

    // 静态方法：通过字节查找枚举
    public static MsgType fromByte(byte code) {
        return MAP.get(code);
    }
}