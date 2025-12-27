package org.marshive.constant;

import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public enum RequestType {
    
    /**
     * 创建房间
     * <ul>
     *     <li>请求体：type - length - roomName (UTF-8字符串)</li>
     *     <li>响应体：0x81 - 数据包长度 - 房间id</li>
     * </ul>
     */
    CREATE((byte) 0x01),
    
    /**
     * 查询房间列表
     * <ul>
     *     <li>请求体：type</li>
     *     <li>响应体：0x82 - 数据包长度 - [count:1]+count*([roomId:4][flags:1][nameLen:1][nameBytes])</li>
     * </ul>
     */
    QUERY((byte) 0x02),  // payload: [count:1] + count*([roomId:4][flags:1][nameLen:1][nameBytes])
    
    /**
     * 加入房间
     * <ul>
     *     <li>请求体：type - roomId (大端序4字节)</li>
     *     <li>
     *         响应体：
     *         <ul>
     *             <li>房客：0x83 - 数据包长度 - 0|1 - roomId</li>
     *             <li>房主：0x84 - 数据包长度 - roomId</li>
     *         </ul>
     *     </li>
     * </ul>
     */
    JOIN((byte) 0x03),
    
    LEAVE((byte) 0x04),  // 仍保留：表示“断开/退出客户端”（会断连接）
    
    /**
     * 开始游戏
     * <ul>
     *     <li>请求体：type</li>
     *     <li>响应体：0x85</li>
     * </ul>
     */
    START((byte) 0x05),
    
    /**
     * 房主退出房间但不断线
     * <ul>
     *     <li>请求体：type</li>
     *     <li>响应体：0x86</li>
     * </ul>
     */
    EXIT_ROOM((byte) 0x06),  // host 退出房间但不断线
    
    /**
     * 房客离开房间但不断线
     * <ul>
     *     <li>请求体：type</li>
     *     <li>
     *         响应体：
     *         <ul>
     *             <li>房客：0x86</li>
     *             <li>房主（如果存在）：0x87 - 数据包长度 - roomId</li>
     *         </ul>
     *     </li>
     * </ul>
     */
    LEAVE_ROOM((byte) 0x07);  // guest 离开房间但不断线
    
    public final byte code;
    
    private static final Map<Byte, RequestType> MAP = new HashMap<>();
    static {
        for (RequestType t : values()) MAP.put(t.code, t);
    }
    
    public static RequestType fromByte(byte code) {
        return MAP.get(code);
    }
    
}
