package org.marshive;

public enum RespType {
    ROOM_CREATED ((byte) 0x81),
    ROOM_LIST    ((byte) 0x82),
    JOIN_RESULT  ((byte) 0x83),
    GUEST_JOINED ((byte) 0x84),
    RELAY_BEGIN  ((byte) 0x85),

    ROOM_EXITED  ((byte) 0x86), // 退出/离开房间成功确认
    GUEST_LEFT   ((byte) 0x87), // guest 离开推送给 host

    ERROR        ((byte) 0xFF);

    public final byte code;
    RespType(byte code) { this.code = code; }
}
