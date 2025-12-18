package org.marshive;

import java.io.*;
import java.net.Socket;
import java.util.UUID;

/**
 * @author Qhbee
 */
public class ClientHandler implements Runnable {

    // 简单的文本响应常量
    private static final String MSG_JOIN_SUCCESS = "JOIN_SUCCESS";
    private static final String MSG_JOIN_FAIL    = "JOIN_FAIL";
    private static final String MSG_GUEST_JOINED = "GUEST_JOINED";
    private static final String MSG_ROOM_CREATED = "ROOM_CREATED:";
    private static final String MSG_ROOM_LIST    = "ROOM_LIST:";

    private final Socket socket;
    private InputStream in;
    private OutputStream out;
    private Room currentRoom;
    private boolean isHost = false;
    private volatile boolean running = true;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            in = socket.getInputStream();
            out = socket.getOutputStream();

            // === 阶段 1: 业务指令处理 (Create/Join/Query) ===
            // 循环读取指令，直到进入游戏状态
            while (running && (currentRoom == null || !currentRoom.isGaming())) {
                int byteRead = in.read();
                if (byteRead == -1) {
                    throw new EOFException("Client disconnected");
                }

                // 将字节转换为枚举
                MsgType type = MsgType.fromByte((byte) byteRead);
                if (type == null) {
                    System.out.println("Unknown msg type: " + byteRead);
                    continue; // 忽略未知指令，或者选择断开
                }

                handleCommand(type);
            }

            // === 阶段 2: 游戏数据盲转发 ===
            // 只有当房间标记为 isGaming=true 时，才会走到这里
            if (currentRoom != null && currentRoom.isGaming()) {
                System.out.println(">>> Transmit Mode Started for: " + socket.getInetAddress());
                forwardLoop(); 
            }

        } catch (IOException e) {
            System.out.println("Connection Closed: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // 使用枚举进行 switch 处理
    private void handleCommand(MsgType type) throws IOException {
        RoomManager roomManager = RoomManager.getInstance();
        switch (type) {
            case CREATE:
                // 协议约定: [Len][NameString]
                int nameLen = in.read();
                byte[] nameBytes = new byte[nameLen];
                readFully(nameBytes);
                String roomName = new String(nameBytes);

                String newRoomId = UUID.randomUUID().toString();
                this.currentRoom = roomManager.createRoom(newRoomId, roomName, this);
                this.isHost = true;
                
                sendSimpleMessage(MSG_ROOM_CREATED + newRoomId);
                break;

            case QUERY:
                String list = roomManager.getAvailableRooms();
                sendSimpleMessage(MSG_ROOM_LIST + list);
                break;

            case JOIN:
                // 协议约定: [36字节 RoomID]
                byte[] idBytes = new byte[36];
                readFully(idBytes);
                String targetId = new String(idBytes);

                if (roomManager.joinRoom(targetId, this)) {
                    this.currentRoom = roomManager.getRoom(targetId);
                    this.isHost = false;
                    sendSimpleMessage(MSG_JOIN_SUCCESS);
                    // 通知房主
                    this.currentRoom.getHost().sendSimpleMessage(MSG_GUEST_JOINED);
                } else {
                    sendSimpleMessage(MSG_JOIN_FAIL);
                }
                break;

            case START:
                // 只有房主能开始
                if (isHost && currentRoom != null && currentRoom.isFull()) {
                    currentRoom.setGaming(true);
                    // 设置状态后，循环结束，进入下面的 forwardLoop
                }
                break;
                
            case LEAVE:
                throw new IOException("User left room");
        }
    }

    // 盲转发逻辑：不管半包粘包，读多少发多少
    private void forwardLoop() throws IOException {
        // 确定转发目标
        ClientHandler opponent = isHost ? currentRoom.getGuest() : currentRoom.getHost();
        OutputStream opponentOut = opponent.socket.getOutputStream();

        byte[] buffer = new byte[1024];
        int len;

        while ((len = in.read(buffer)) != -1) {
            opponentOut.write(buffer, 0, len);
            opponentOut.flush(); // 必须立即 flush
        }
    }

    // 辅助：发送简单字符串消息
    public void sendSimpleMessage(String msg) throws IOException {
        out.write(msg.getBytes());
        out.flush();
    }

    // 辅助：确保读满指定长度
    private void readFully(byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = in.read(buffer, total, buffer.length - total);
            if (count == -1) {
                throw new EOFException();
            }
            total += count;
        }
    }

    // 清理资源：断开一方，销毁房间，踢掉另一方
    private void cleanup() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}

        if (currentRoom != null) {
            ClientHandler opponent = isHost ? currentRoom.getGuest() : currentRoom.getHost();
            
            // 连锁断开机制
            if (opponent != null && !opponent.socket.isClosed()) {
                try {
                    opponent.socket.close(); 
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            // 从管理器移除房间
            RoomManager.getInstance().removeRoom(currentRoom.getId());
        }
    }
}