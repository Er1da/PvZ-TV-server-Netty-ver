package org.marshive.dao;

import lombok.extern.slf4j.Slf4j;
import org.marshive.domain.Client;
import org.marshive.domain.Room;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RoomDAO {
    private static final RoomDAO INSTANCE = new RoomDAO();
    private RoomDAO() {}
    public static RoomDAO getInstance() { return INSTANCE; }
    
    private final Map<Integer, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger(1000);
    
    public Room createRoom(String name, Client host) {
        int id = idGen.incrementAndGet();
        Room r = new Room(id, name, host);
        host.setHost(true);
        host.setCurrentRoom(r);
        rooms.put(id, r);
        
        host.getChannel().closeFuture().addListener(f -> removeRoom(id));
        
        log.debug("[ip={}] 创建房间: {}", host.getChannel().remoteAddress(), r);
        return r;
    }
    
    public Room getRoom(int id) { return rooms.get(id); }
    
    public synchronized boolean joinRoom(int id, Client guest) {
        Room r = rooms.get(id);
        if (r != null && !r.isFull() && !r.isGaming()) {
            r.setGuest(guest);
            guest.setHost(false);
            guest.setCurrentRoom(r);
            log.debug("[ip={}] 加入房间: {}", guest.getChannel().remoteAddress(), r);
            return true;
        }
        return false;
    }
    
    // guest 离开：只把 guest 置空，房间保留
    public synchronized boolean leaveAsGuest(int id, Client guest) {
        Room r = rooms.get(id);
        if (r == null) return false;
        if (r.getGuest() != guest) return false;
        if (r.isGaming()) return false; // 游戏中不允许离开（你也可以允许）
        r.setGuest(null);
        guest.setCurrentRoom(null);
        return true;
    }
    
    public void removeRoom(int id) {
        Room r = rooms.remove(id);
        if (r != null) {
            log.debug("[ip={}] 解散房间: {}", r.getHost().getChannel().remoteAddress(), r);
        }
    }
    
    public Collection<Room> allRooms() { return rooms.values(); }
}
