package org.marshive;

import java.io.*;
import java.net.Socket;
import java.util.List;

/**
 * 客户端连接处理器
 * 处理单个客户端连接的消息接收和发送
 */
public class ClientHandler implements Runnable {
    private final Socket clientSocket;
    private final RoomManager roomManager;
    private Room currentRoom;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private volatile boolean running = true;

    public ClientHandler(Socket clientSocket, RoomManager roomManager) {
        this.clientSocket = clientSocket;
        this.roomManager = roomManager;
        try {
            // 设置TCP_NODELAY禁用Nagle算法
            this.clientSocket.setTcpNoDelay(true);
            this.inputStream = new DataInputStream(clientSocket.getInputStream());
            this.outputStream = new DataOutputStream(clientSocket.getOutputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            while (running && !clientSocket.isClosed()) {
                // 读取消息类型
                byte messageType = inputStream.readByte();
                
                // 如果已经进入游戏状态，直接转发数据
                if (currentRoom != null && currentRoom.isGameStarted()) {
                    handleInGameMessage(messageType);
                } else {
                    // 处理房间操作消息
                    handleRoomOperation(messageType);
                }
            }
        } catch (IOException e) {
            // 连接断开或其他IO异常
            System.out.println("Client disconnected: " + clientSocket.getRemoteSocketAddress());
        } finally {
            cleanup();
        }
    }

    /**
     * 处理游戏进行中的消息（直接转发）
     * @param messageType 消息类型
     * @throws IOException IO异常
     */
    private void handleInGameMessage(byte messageType) throws IOException {
        // 读取剩余的所有数据并转发给对方
        // 在游戏状态下，除了消息类型外的所有数据都需要转发
        
        // 先发送消息类型给对方
        Socket otherPlayer = currentRoom.getOtherPlayer(clientSocket);
        if (otherPlayer != null && !otherPlayer.isClosed()) {
            DataOutputStream otherOutput = new DataOutputStream(otherPlayer.getOutputStream());
            otherOutput.writeByte(messageType);
            
            // 转发剩余数据
            forwardRemainingData(otherOutput);
        }
    }

    /**
     * 转发剩余数据
     * @param otherOutput 对方输出流
     * @throws IOException IO异常
     */
    private void forwardRemainingData(DataOutputStream otherOutput) throws IOException {
        // 注意：由于TCP流特性，这里可能不是完整的消息数据
        // 但按照要求，在游戏状态下不需要处理粘包半包问题，由客户端处理
        byte[] buffer = new byte[1024];
        int bytesRead;
        while (inputStream.available() > 0) {
            bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                otherOutput.write(buffer, 0, bytesRead);
            }
        }
        otherOutput.flush();
    }

    /**
     * 处理房间操作消息
     * @param messageType 消息类型
     * @throws IOException IO异常
     */
    private void handleRoomOperation(byte messageType) throws IOException {
        int typeValue = Byte.toUnsignedInt(messageType);
        int dataLength = MsgType.getDataLength(typeValue);
        
        byte[] data;
        if (dataLength == 0) {
            data = new byte[0];
        } else if (dataLength > 0) {
            // 固定长度数据
            data = new byte[dataLength];
            inputStream.readFully(data);
        } else {
            // 可变长度数据，读取剩余所有可用数据
            data = readAvailableData();
        }
        
        switch (MsgType.fromTypeValue(typeValue)) {
            case CREATE_ROOM:
                handleCreateRoom(data);
                break;
            case QUERY_ROOMS:
                handleQueryRooms();
                break;
            case JOIN_ROOM:
                handleJoinRoom(data);
                break;
            case LEAVE_ROOM:
                handleLeaveRoom();
                break;
            case EXIT_ROOM:
                handleExitRoom();
                break;
            case START_GAME:
                handleStartGame();
                break;
            default:
                System.out.println("Unknown message type: " + typeValue);
                break;
        }
    }

    /**
     * 读取当前可用的所有数据
     * @return 数据字节数组
     * @throws IOException IO异常
     */
    private byte[] readAvailableData() throws IOException {
        byte[] data = new byte[inputStream.available()];
        inputStream.readFully(data);
        return data;
    }

    /**
     * 处理创建房间请求
     * @param data 房间名数据
     * @throws IOException IO异常
     */
    private void handleCreateRoom(byte[] data) throws IOException {
        String roomName = new String(data);
        currentRoom = roomManager.createRoom(roomName, clientSocket);
        
        // 发送创建成功的响应（房间ID）
        outputStream.writeInt(currentRoom.getRoomId());
        outputStream.flush();
        System.out.println("Room created: " + roomName + ", ID: " + currentRoom.getRoomId());
    }

    /**
     * 处理查询房间列表请求
     * @throws IOException IO异常
     */
    private void handleQueryRooms() throws IOException {
        List<Room> availableRooms = roomManager.getAvailableRooms();
        
        // 发送房间数量
        outputStream.writeInt(availableRooms.size());
        
        // 发送每个房间的信息
        for (Room room : availableRooms) {
            outputStream.writeInt(room.getRoomId());
            outputStream.writeUTF(room.getRoomName());
        }
        outputStream.flush();
        System.out.println("Sent " + availableRooms.size() + " rooms to client");
    }

    /**
     * 处理加入房间请求
     * @param data 包含房间ID的数据
     * @throws IOException IO异常
     */
    private void handleJoinRoom(byte[] data) throws IOException {
        if (data.length >= 4) {
            int roomId = ((data[0] & 0xFF) << 24) | 
                         ((data[1] & 0xFF) << 16) | 
                         ((data[2] & 0xFF) << 8) | 
                         (data[3] & 0xFF);
                         
            Room room = roomManager.joinRoom(roomId, clientSocket);
            if (room != null) {
                currentRoom = room;
                // 发送成功响应
                outputStream.writeByte(1);
                System.out.println("Client joined room: " + roomId);
            } else {
                // 发送失败响应
                outputStream.writeByte(0);
                System.out.println("Failed to join room: " + roomId);
            }
            outputStream.flush();
        }
    }

    /**
     * 处理离开房间请求
     * @throws IOException IO异常
     */
    private void handleLeaveRoom() throws IOException {
        if (currentRoom != null) {
            int roomId = currentRoom.getRoomId();
            roomManager.leaveRoom(roomId, clientSocket);
            currentRoom = null;
            System.out.println("Client left room");
        }
    }

    /**
     * 处理退出房间请求（断开连接）
     */
    private void handleExitRoom() {
        running = false;
        System.out.println("Client exited room, closing connection");
    }

    /**
     * 处理开始游戏请求
     * @throws IOException IO异常
     */
    private void handleStartGame() throws IOException {
        if (currentRoom != null) {
            currentRoom.setGameStarted(true);
            // 通知双方进入游戏状态
            notifyGameStart();
            System.out.println("Game started in room: " + currentRoom.getRoomId());
        }
    }

    /**
     * 通知双方开始游戏
     * @throws IOException IO异常
     */
    private void notifyGameStart() throws IOException {
        // 通知房主
        if (currentRoom.getHostSocket() != null && !currentRoom.getHostSocket().isClosed()) {
            DataOutputStream hostOut = new DataOutputStream(currentRoom.getHostSocket().getOutputStream());
            hostOut.writeByte(MsgType.START_GAME.getTypeValue());
            hostOut.flush();
        }
        
        // 通知访客
        if (currentRoom.getGuestSocket() != null && !currentRoom.getGuestSocket().isClosed()) {
            DataOutputStream guestOut = new DataOutputStream(currentRoom.getGuestSocket().getOutputStream());
            guestOut.writeByte(MsgType.START_GAME.getTypeValue());
            guestOut.flush();
        }
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        running = false;
        try {
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
            
            // 如果在房间中，通知房间管理器
            if (currentRoom != null) {
                roomManager.leaveRoom(currentRoom.getRoomId(), clientSocket);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Socket getClientSocket() {
        return clientSocket;
    }

    public Room getCurrentRoom() {
        return currentRoom;
    }
}