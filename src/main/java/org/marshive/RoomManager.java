package org.marshive;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RoomManager {

    // 1. 私有静态实例 (饿汉式单例，线程安全)
    private static final RoomManager INSTANCE = new RoomManager();

    // 2. 私有构造方法，防止外部 new
    private RoomManager() {}

    // 3. 公共静态访问点
    public static RoomManager getInstance() {
        return INSTANCE;
    }

    // 线程安全的 Map 存储房间
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    // 创建房间
    public Room createRoom(String id, String name, ClientHandler host) {
        Room room = new Room(id, name, host);
        rooms.put(id, room);
        System.out.println("Room Created: " + id + " [" + name + "]");
        return room;
    }

    // 获取房间
    public Room getRoom(String id) {
        return rooms.get(id);
    }

    // 加入房间
    public synchronized boolean joinRoom(String id, ClientHandler guest) {
        Room room = rooms.get(id);
        // 只有房间存在、未满、且没开始游戏时才能加入
        if (room != null && !room.isFull() && !room.isGaming()) {
            room.setGuest(guest);
            return true;
        }
        return false;
    }

    // 移除房间
    public void removeRoom(String id) {
        if (id != null && rooms.containsKey(id)) {
            rooms.remove(id);
            System.out.println("Room Removed: " + id);
        }
    }

    // 获取可加入房间列表
    public String getAvailableRooms() {
        return rooms.values().stream()
                .filter(r -> !r.isFull() && !r.isGaming())
                .map(r -> r.getId() + ":" + r.getName())
                .collect(Collectors.joining(","));
    }
}