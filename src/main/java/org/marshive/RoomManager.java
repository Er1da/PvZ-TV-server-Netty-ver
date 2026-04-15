package org.marshive;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomManager {
    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger idGen;
    private final int shardId;

    public RoomManager(int shardId) {
        this.shardId = shardId;

        // ✅ 让不同端口 roomId 不撞：高位塞 shardId
        // 例如 shardId=1 的 roomId 都 >= 0x01000000
        int base = (shardId & 0x7F) << 24; // 0..127 shards 安全
        this.idGen = new AtomicInteger(base + 1000);
    }

    public Room createRoom(String name, ClientHandler host, int protocolVersion) {
        int id = idGen.incrementAndGet();
        Room r = new Room(id, name, host, protocolVersion);
        rooms.put(id, r);
        System.out.println("[shard=" + shardId + "] Room Created: " + id + " [" + name + "]");
        return r;
    }

    public Room getRoom(int id) { return rooms.get(id); }

    public synchronized boolean joinRoom(int id, ClientHandler guest) {
        Room r = rooms.get(id);
        if (r != null && !r.isFull() && !r.isGaming()) {
            r.setGuest(guest);
            return true;
        }
        return false;
    }

    public synchronized boolean leaveAsGuest(int id, ClientHandler guest) {
        Room r = rooms.get(id);
        if (r == null) return false;
        if (r.getGuest() != guest) return false;
        if (r.isGaming()) return false;
        r.setGuest(null);
        return true;
    }

    public void removeRoom(int id) {
        Room r = rooms.remove(id);
        if (r != null) System.out.println("[shard=" + shardId + "] Room Removed: " + id);
    }

    public Iterable<Room> allRooms() { return rooms.values(); }
}
