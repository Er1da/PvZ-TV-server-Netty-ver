package org.marshive.parse;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端转发数据包的事件类型枚举。
 * 所有数据包继承自 BaseEvent: [type:1][size:1][payload:0~n]
 */
public enum EventType {
    // 基础事件
    EVENT_START_GAME((byte) 0x03, "开始游戏", 2),
    
    // 客户端 → 服务器（房客 → 房主）
    EVENT_CLIENT_BOARD_TOUCH_DOWN((byte) 0x0D, "触摸按下", 6),
    EVENT_CLIENT_BOARD_TOUCH_DRAG((byte) 0x0E, "触摸拖动", 6),
    EVENT_CLIENT_BOARD_TOUCH_UP((byte) 0x0F, "触摸抬起", 6),
    EVENT_CLIENT_BOARD_TOUCH_CLEAR_CURSOR((byte) 0x16, "清除光标", 2),
    EVENT_CLIENT_BOARD_GAMEPAD_SET_STATE((byte) 0x18, "手柄状态", 3),
    EVENT_CLIENT_BOARD_PAUSE((byte) 0x1C, "暂停", 3),
    EVENT_CLIENT_BOARD_CONCEDE((byte) 0x1E, "认输", 2),
    
    // 服务器 → 客户端（房主 → 房客） - 触摸/手柄响应
    EVENT_BOARD_TOUCH_DOWN_REPLY((byte) 0x10, "触摸按下回复", 4),
    EVENT_BOARD_TOUCH_DRAG_REPLY((byte) 0x11, "触摸拖动回复", 6),
    EVENT_BOARD_TOUCH_UP_REPLY((byte) 0x12, "触摸抬起回复", 4),
    EVENT_SERVER_BOARD_TOUCH_DOWN((byte) 0x13, "触摸按下同步", 8),
    EVENT_SERVER_BOARD_TOUCH_DRAG((byte) 0x14, "触摸拖动同步", 6),
    EVENT_SERVER_BOARD_TOUCH_UP((byte) 0x15, "触摸抬起同步", 4),
    EVENT_SERVER_BOARD_TOUCH_CLEAR_CURSOR((byte) 0x17, "清除光标", 2),
    EVENT_SERVER_BOARD_GAMEPAD_SET_STATE((byte) 0x19, "手柄状态同步", 3),
    EVENT_SERVER_BOARD_PAUSE((byte) 0x1D, "暂停同步", 3),
    EVENT_SERVER_BOARD_CONCEDE((byte) 0x1F, "认输同步", 2),
    
    // 游戏对象事件
    EVENT_SERVER_BOARD_COIN_ADD((byte) 0x20, "金币添加", 8),
    EVENT_SERVER_BOARD_GRIDITEM_DIE((byte) 0x21, "场地物死亡", 4),
    EVENT_SERVER_BOARD_GRIDITEM_LAUNCHCOUNTER((byte) 0x22, "场地物计数器", 6),
    EVENT_SERVER_BOARD_GRIDITEM_ADDGRAVE((byte) 0x23, "添加墓碑", 8),
    EVENT_SERVER_BOARD_PLANT_LAUNCHCOUNTER((byte) 0x24, "植物计数器", 6),
    EVENT_SERVER_BOARD_PLANT_FIRE((byte) 0x2A, "植物开火", 14),
    EVENT_SERVER_BOARD_PLANT_ADD((byte) 0x2B, "植物添加", 14),
    EVENT_SERVER_BOARD_PLANT_DIE((byte) 0x2C, "植物死亡", 4),
    EVENT_SERVER_BOARD_ZOMBIE_DIE((byte) 0x2E, "僵尸死亡", 4),
    EVENT_SERVER_BOARD_ZOMBIE_ADD((byte) 0x30, "僵尸添加", 16),
    EVENT_SERVER_BOARD_ZOMBIE_RIZE_FORM_GRAVE((byte) 0x33, "僵尸从墓碑升起", 6),
    EVENT_SERVER_BOARD_ZOMBIE_PICK_SPEED((byte) 0x35, "僵尸速度", 14),
    EVENT_SERVER_BOARD_LAWNMOWER_START((byte) 0x3D, "小推车启动", 4),
    EVENT_SERVER_BOARD_TAKE_SUNMONEY((byte) 0x3E, "收集阳光", 4),
    EVENT_SERVER_BOARD_TAKE_DEATHMONEY((byte) 0x3F, "收集死亡掉落", 4),
    EVENT_SERVER_BOARD_SEEDPACKET_WASPLANTED((byte) 0x40, "种子包种下", 4),
    EVENT_SERVER_BOARD_START_LEVEL((byte) 0x41, "关卡开始", 20),
    
    // 对战结果
    EVENT_CLIENT_VSRESULT_BUTTON_DEPRESS((byte) 0x42, "结果界面按钮(C)", 3),
    EVENT_SERVER_VSRESULT_BUTTON_DEPRESS((byte) 0x43, "结果界面按钮(S)", 3);
    
    public final byte code;
    public final String name;
    public final int size;
    
    EventType(byte code, String name, int size) {
        this.code = code;
        this.name = name;
        this.size = size;
    }
    
    private static final Map<Byte, EventType> MAP = new HashMap<>();
    static {
        for (EventType t : values()) {
            MAP.put(t.code, t);
        }
    }
    
    /**
     * 根据字节码获取事件类型
     * @param code 事件类型字节码
     * @return 对应的事件类型，如果不存在则返回null
     */
    public static EventType fromByte(byte code) {
        return MAP.get(code);
    }
    
    /**
     * 根据字节码获取事件名称
     * @param code 事件类型字节码
     * @return 对应的事件名称，如果不存在则返回"未知事件"
     */
    public static String getNameByCode(byte code) {
        EventType type = MAP.get(code);
        return type != null ? type.name : "未知事件";
    }
    
    /**
     * 根据字节码获取数据包大小
     * @param code 事件类型字节码
     * @return 数据包大小，如果类型未知则返回-1
     */
    public static int getSizeByCode(byte code) {
        EventType type = MAP.get(code);
        return type != null ? type.size : -1;
    }
}
