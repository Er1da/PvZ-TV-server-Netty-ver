package org.marshive;

/**
 * 游戏消息类型枚举
 * 每个消息由1字节类型标识符和后续可变长度的数据组成
 */
public enum MsgType {
    // 房间相关操作
    CREATE_ROOM(1),        // 创建房间
    QUERY_ROOMS(2),        // 查询房间列表
    JOIN_ROOM(3),          // 加入房间
    LEAVE_ROOM(4),         // 离开房间
    EXIT_ROOM(5),          // 退出房间（断开连接）
    START_GAME(6),         // 开始游戏（进入转发模式）
    
    // 游戏过程中的数据传输（进入游戏后直接转发）
    GAME_DATA(7);          // 游戏数据（原样转发）

    private final int typeValue;

    MsgType(int typeValue) {
        this.typeValue = typeValue;
    }

    public int getTypeValue() {
        return typeValue;
    }

    public static MsgType fromTypeValue(int typeValue) {
        for (MsgType type : MsgType.values()) {
            if (type.getTypeValue() == typeValue) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message type: " + typeValue);
    }
    
    /**
     * 根据消息类型获取数据长度
     * 对于可变长度的数据，返回-1表示长度可变
     * @param typeValue 消息类型值
     * @return 数据长度，-1表示可变长度
     */
    public static int getDataLength(int typeValue) {
        switch (typeValue) {
            case 1: // CREATE_ROOM - 假设包含房间名，长度可变
                return -1;
            case 2: // QUERY_ROOMS - 不需要额外数据
                return 0;
            case 3: // JOIN_ROOM - 假设包含房间ID，长度固定
                return 4; // 例如4字节的房间ID
            case 4: // LEAVE_ROOM - 不需要额外数据
                return 0;
            case 5: // EXIT_ROOM - 不需要额外数据
                return 0;
            case 6: // START_GAME - 不需要额外数据
                return 0;
            case 7: // GAME_DATA - 可变长度数据
                return -1;
            default:
                throw new IllegalArgumentException("Unknown message type: " + typeValue);
        }
    }
}