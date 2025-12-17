package org.marshive;

/**
 * @author Qhbee
 */
public class Room {
    private String id;
    private String name;
    private ClientHandler host;
    private ClientHandler guest;
    
    // 游戏状态标志，volatile 确保 host 开启游戏时 guest 能立刻感知
    private volatile boolean isGaming = false;

    public Room(String id, String name, ClientHandler host) {
        this.id = id;
        this.name = name;
        this.host = host;
    }

    public boolean isFull() {
        return host != null && guest != null;
    }

    // Getters & Setters
    public String getId() { return id; }
    public String getName() { return name; }
    public ClientHandler getHost() { return host; }
    public ClientHandler getGuest() { return guest; }
    public void setGuest(ClientHandler guest) { this.guest = guest; }
    
    public boolean isGaming() { return isGaming; }
    public void setGaming(boolean gaming) { isGaming = gaming; }
}