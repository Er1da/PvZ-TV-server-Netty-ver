package org.marshive;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RoomManager {
    private static final RoomManager INSTANCE = new RoomManager();
    private RoomManager() {}
    public static RoomManager getInstance() { return INSTANCE; }

    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger(1000);

    public Room createRoom(String name, ClientHandler host) {
        int id = idGen.incrementAndGet();
        Room r = new Room(id, name, host);
        rooms.put(id, r);
        System.out.println("Room Created: " + id + " [" + name + "]");
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

    // guest 离开：只把 guest 置空，房间保留
    public synchronized boolean leaveAsGuest(int id, ClientHandler guest) {
        Room r = rooms.get(id);
        if (r == null) return false;
        if (r.getGuest() != guest) return false;
        if (r.isGaming()) return false; // 游戏中不允许离开（你也可以允许）
        r.setGuest(null);
        return true;
    }

    public void removeRoom(int id) {
        Room r = rooms.remove(id);
        if (r != null) System.out.println("Room Removed: " + id);
    }

    public Iterable<Room> allRooms() { return rooms.values(); }
}
