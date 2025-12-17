package org.marshive;

import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * 房间管理器
 * 负责管理所有游戏房间的创建、查询、加入等操作
 */
public class RoomManager {
    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    
    /**
     * 创建新房间
     * @param roomName 房间名称
     * @param hostSocket 房主Socket
     * @return 创建的房间
     */
    public Room createRoom(String roomName, Socket hostSocket) {
        Room room = new Room(roomName, hostSocket);
        rooms.put(room.getRoomId(), room);
        return room;
    }
    
    /**
     * 根据房间ID查找房间
     * @param roomId 房间ID
     * @return 房间对象，如果未找到则返回null
     */
    public Room findRoom(int roomId) {
        return rooms.get(roomId);
    }
    
    /**
     * 获取所有可用房间列表（未满员且未开始游戏的房间）
     * @return 可用房间列表
     */
    public List<Room> getAvailableRooms() {
        List<Room> availableRooms = new ArrayList<Room>();
        for (Room room : rooms.values()) {
            if (!room.isFull() && !room.isGameStarted()) {
                availableRooms.add(room);
            }
        }
        return availableRooms;
    }
    
    /**
     * 加入房间
     * @param roomId 房间ID
     * @param guestSocket 访客Socket
     * @return 加入的房间，如果无法加入则返回null
     */
    public Room joinRoom(int roomId, Socket guestSocket) {
        Room room = rooms.get(roomId);
        if (room != null && !room.isFull() && !room.isGameStarted()) {
            room.setGuestSocket(guestSocket);
            return room;
        }
        return null;
    }
    
    /**
     * 玩家离开房间
     * @param roomId 房间ID
     * @param playerSocket 玩家Socket
     */
    public void leaveRoom(int roomId, Socket playerSocket) {
        Room room = rooms.get(roomId);
        if (room != null) {
            room.removePlayer(playerSocket);
            // 如果房间为空，则删除房间
            if (room.isEmpty()) {
                rooms.remove(roomId);
                room.close();
            }
        }
    }
    
    /**
     * 删除房间
     * @param roomId 房间ID
     */
    public void removeRoom(int roomId) {
        Room room = rooms.remove(roomId);
        if (room != null) {
            room.close();
        }
    }
    
    /**
     * 获取房间数量
     * @return 房间总数
     */
    public int getRoomCount() {
        return rooms.size();
    }
}