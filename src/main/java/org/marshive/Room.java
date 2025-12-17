package org.marshive;

import lombok.Data;

/**
 * @author Qhbee
 */
@Data
public class Room {
    private String id;
    private String name;
    private ClientHandler host;
    private ClientHandler guest;
    
    // 游戏状态标志，volatile 确保 host 开启游戏时 guest 能立刻感知
    private volatile boolean gaming = false;

    public Room(String id, String name, ClientHandler host) {
        this.id = id;
        this.name = name;
        this.host = host;
    }

    public boolean isFull() {
        return host != null && guest != null;
    }
}