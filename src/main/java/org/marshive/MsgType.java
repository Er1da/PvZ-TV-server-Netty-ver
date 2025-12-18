package org.marshive;

import java.util.HashMap;
import java.util.Map;

public enum MsgType {
    CREATE((byte) 0x01),
    QUERY ((byte) 0x02),
    JOIN  ((byte) 0x03),
    LEAVE ((byte) 0x04), // 仍保留：表示“断开/退出客户端”（会断连接）
    START ((byte) 0x05),

    EXIT_ROOM ((byte) 0x06), // host 退出房间但不断线
    LEAVE_ROOM((byte) 0x07); // guest 离开房间但不断线

    public final byte code;

    private static final Map<Byte, MsgType> MAP = new HashMap<>();
    static {
        for (MsgType t : values()) MAP.put(t.code, t);
    }

    MsgType(byte code) { this.code = code; }

    public static MsgType fromByte(byte code) {
        return MAP.get(code);
    }
}
