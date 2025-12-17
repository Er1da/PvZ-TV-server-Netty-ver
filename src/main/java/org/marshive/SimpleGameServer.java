package org.marshive;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class SimpleGameServer {

    // ================= 配置与常量 =================
    private static final int PORT = 8888;

    // 协议定义 (1字节 Type)
    private static final byte TYPE_CREATE = 0x01; // 创建房间
    private static final byte TYPE_QUERY  = 0x02; // 查询房间列表
    private static final byte TYPE_JOIN   = 0x03; // 加入房间
    private static final byte TYPE_LEAVE  = 0x04; // 离开房间
    private static final byte TYPE_START  = 0x05; // 开始游戏 (进入转发模式)

    // 全局房间管理器
    private static final RoomManager roomManager = new RoomManager();

    public static void main(String[] args) {
        System.out.println(">>> 游戏服务器启动，监听端口: " + PORT);
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                // 关键点：开启 TCP_NODELAY，禁用 Nagle 算法，降低延迟
                clientSocket.setTcpNoDelay(true); 
                
                // 为每个客户端启动一个独立线程
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ================= 核心：客户端处理线程 =================
    static class ClientHandler implements Runnable {
        private Socket socket;
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

                // === 阶段一：指令交互模式 ===
                while (running && currentRoom == null || (currentRoom != null && !currentRoom.isGaming)) {
                    int type = in.read(); // 读取 1 字节 Type
                    if (type == -1) break; // 客户端断开

                    handleCommand((byte) type);
                }

                // === 阶段二：游戏转发模式 ===
                // 只有当房间状态变为 isGaming 时，循环才会结束并走到这里
                if (currentRoom != null && currentRoom.isGaming) {
                    System.out.println(">>> 进入转发模式: " + socket.getInetAddress());
                    forwardLoop(); 
                }

            } catch (IOException e) {
                System.out.println("连接异常/断开: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        // 处理指令逻辑
        private void handleCommand(byte type) throws IOException {
            switch (type) {
                case TYPE_CREATE: // 假设 Data 是 1 字节的房间名长度 + 房间名 String
                    int nameLen = in.read();
                    byte[] nameBytes = new byte[nameLen];
                    readFully(in, nameBytes);
                    String roomName = new String(nameBytes);
                    
                    // 创建房间逻辑
                    String roomId = UUID.randomUUID().toString().substring(0, 4); // 简单ID
                    Room newRoom = roomManager.createRoom(roomId, roomName, this);
                    this.currentRoom = newRoom;
                    this.isHost = true;
                    
                    sendMessage("ROOM_CREATED:" + roomId);
                    break;

                case TYPE_QUERY: // 假设没有 Data
                    String list = roomManager.getAvailableRooms();
                    sendMessage("ROOM_LIST:" + list);
                    break;

                case TYPE_JOIN: // 假设 Data 是 4 字节 RoomID
                    byte[] idBytes = new byte[4];
                    readFully(in, idBytes);
                    String joinId = new String(idBytes);
                    
                    if (roomManager.joinRoom(joinId, this)) {
                        this.currentRoom = roomManager.getRoom(joinId);
                        this.isHost = false;
                        sendMessage("JOIN_SUCCESS");
                        // 通知房主有人来了
                        this.currentRoom.host.sendMessage("GUEST_JOINED");
                    } else {
                        sendMessage("JOIN_FAIL");
                    }
                    break;

                case TYPE_START: // 只有房主能发
                    if (isHost && currentRoom != null && currentRoom.isFull()) {
                        currentRoom.isGaming = true;
                        // 这里不需要回复，因为状态改变后，run() 里的 while 循环会检测到 isGaming 退出，
                        // 然后进入 forwardLoop。
                        // 注意：房主发了这个包后，自己也要立刻切换到接收转发状态。
                    }
                    break;

                case TYPE_LEAVE:
                    cleanup(); // 视为断开处理
                    break;
            }
        }

        // === 核心转发逻辑 ===
        // 盲转发：不管半包粘包，读到多少发多少
        private void forwardLoop() throws IOException {
            ClientHandler opponent = isHost ? currentRoom.guest : currentRoom.host;
            byte[] buffer = new byte[1024]; 
            int len;
            
            // 只要读到数据，就直接塞给对手的 Output
            while ((len = in.read(buffer)) != -1) {
                opponent.out.write(buffer, 0, len);
                opponent.out.flush(); // 必须立即刷新
            }
        }

        // 辅助方法：发送简单的文本消息给客户端
        public void sendMessage(String msg) {
            try {
                byte[] data = msg.getBytes();
                // 简单协议：为了防止粘包，这里可以加个长度头，或者简单起见直接发(测试用)
                // 实际建议：[Type:Response][Len][Data]
                out.write(data);
                out.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // 辅助方法：确保读满指定长度 (解决指令阶段的半包)
        private void readFully(InputStream is, byte[] buffer) throws IOException {
            int total = 0;
            while (total < buffer.length) {
                int count = is.read(buffer, total, buffer.length - total);
                if (count == -1) throw new EOFException();
                total += count;
            }
        }

        // 清理资源：断开时调用
        private void cleanup() {
            running = false;
            try { socket.close(); } catch (IOException ignored) {}
            
            if (currentRoom != null) {
                // 如果房间还在，说明我是意外掉线，需要把对方也踢了
                ClientHandler opponent = isHost ? currentRoom.guest : currentRoom.host;
                if (opponent != null && opponent.running) {
                    try {
                        opponent.socket.close(); // 关闭对方 Socket，对方线程会抛出异常并退出
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                roomManager.removeRoom(currentRoom.id); // 从列表移除
                System.out.println("房间已销毁: " + currentRoom.id);
            }
        }
    }

    // ================= 房间管理类 =================
    static class RoomManager {
        private ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

        public Room createRoom(String id, String name, ClientHandler host) {
            Room room = new Room(id, name, host);
            rooms.put(id, room);
            System.out.println("创建房间: " + id);
            return room;
        }

        public Room getRoom(String id) {
            return rooms.get(id);
        }

        public boolean joinRoom(String id, ClientHandler guest) {
            Room room = rooms.get(id);
            if (room != null && !room.isFull() && !room.isGaming) {
                room.guest = guest;
                return true;
            }
            return false;
        }

        public String getAvailableRooms() {
            return rooms.values().stream()
                .filter(r -> !r.isFull() && !r.isGaming)
                .map(r -> r.id + ":" + r.name)
                .collect(Collectors.joining(","));
        }

        public void removeRoom(String id) {
            if (id != null) rooms.remove(id);
        }
    }

    // ================= 房间实体 =================
    static class Room {
        String id;
        String name;
        ClientHandler host;
        ClientHandler guest;
        volatile boolean isGaming = false; // volatile 保证可见性

        public Room(String id, String name, ClientHandler host) {
            this.id = id;
            this.name = name;
            this.host = host;
        }

        public boolean isFull() {
            return host != null && guest != null;
        }
    }
}