package org.marshive;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 游戏房间类
 * 代表一个游戏房间，包含房主和访客的信息
 */
public class Room {
    private static final AtomicInteger roomIdGenerator = new AtomicInteger(0);
    
    private final int roomId;
    private final String roomName;
    private final Socket hostSocket;
    private Socket guestSocket;
    private boolean gameStarted = false;
    
    public Room(String roomName, Socket hostSocket) {
        this.roomId = roomIdGenerator.incrementAndGet();
        this.roomName = roomName;
        this.hostSocket = hostSocket;
        this.guestSocket = null;
    }
    
    public int getRoomId() {
        return roomId;
    }
    
    public String getRoomName() {
        return roomName;
    }
    
    public Socket getHostSocket() {
        return hostSocket;
    }
    
    public Socket getGuestSocket() {
        return guestSocket;
    }
    
    public void setGuestSocket(Socket guestSocket) {
        this.guestSocket = guestSocket;
    }
    
    public boolean isFull() {
        return guestSocket != null;
    }
    
    public boolean isEmpty() {
        return hostSocket == null && guestSocket == null;
    }
    
    public boolean isGameStarted() {
        return gameStarted;
    }
    
    public void setGameStarted(boolean gameStarted) {
        this.gameStarted = gameStarted;
    }
    
    /**
     * 获取房间内的另一个玩家的Socket
     * @param currentPlayer 当前玩家的Socket
     * @return 另一个玩家的Socket，如果找不到则返回null
     */
    public Socket getOtherPlayer(Socket currentPlayer) {
        if (currentPlayer == null) {
            return null;
        }
        
        if (currentPlayer.equals(hostSocket)) {
            return guestSocket;
        } else if (currentPlayer.equals(guestSocket)) {
            return hostSocket;
        }
        
        return null;
    }
    
    /**
     * 移除指定玩家
     * @param playerSocket 要移除的玩家Socket
     */
    public void removePlayer(Socket playerSocket) {
        if (playerSocket == null) {
            return;
        }

        try {
            if (playerSocket.equals(hostSocket)) {
                this.hostSocket.close();
            } else if (playerSocket.equals(guestSocket)) {
                this.guestSocket.close();
                this.guestSocket = null;
            }
        } catch (IOException e) {
            // 忽略关闭异常
        }
    }
    
    /**
     * 关闭房间，断开所有连接
     */
    public void close() {
        try {
            if (hostSocket != null && !hostSocket.isClosed()) {
                hostSocket.close();
            }
            
            if (guestSocket != null && !guestSocket.isClosed()) {
                guestSocket.close();
            }
        } catch (Exception e) {
            // 忽略关闭异常
        }
    }
}