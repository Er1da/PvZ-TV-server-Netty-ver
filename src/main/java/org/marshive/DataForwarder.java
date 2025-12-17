package org.marshive;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * 游戏数据转发器
 * 负责在游戏开始后转发两个玩家之间的数据
 */
public class DataForwarder {
    
    /**
     * 在两个玩家之间转发数据
     * @param from 发送方Socket
     * @param to 接收方Socket
     * @param data 数据
     * @throws IOException IO异常
     */
    public static void forwardData(Socket from, Socket to, byte[] data) throws IOException {
        if (to != null && !to.isClosed()) {
            DataOutputStream out = new DataOutputStream(to.getOutputStream());
            out.write(data);
            out.flush();
        }
    }
    
    /**
     * 在房间内转发数据（从一个玩家转发给另一个玩家）
     * @param room 游戏房间
     * @param from 发送方Socket
     * @param data 数据
     * @throws IOException IO异常
     */
    public static void forwardToRoomMate(Room room, Socket from, byte[] data) throws IOException {
        if (room == null) {
            return;
        }
        
        Socket otherPlayer = room.getOtherPlayer(from);
        if (otherPlayer != null && !otherPlayer.isClosed()) {
            DataOutputStream out = new DataOutputStream(otherPlayer.getOutputStream());
            out.write(data);
            out.flush();
        }
    }
}