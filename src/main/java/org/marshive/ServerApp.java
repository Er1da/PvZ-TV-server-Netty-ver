package org.marshive;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ServerApp {

    private static final int PORT = 8888;

    public static void main(String[] args) {
        // 初始化房间管理器
        RoomManager roomManager = new RoomManager();

        System.out.println(">>> Game Server Started on Port: " + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();

                // 核心配置：开启 TCP_NODELAY，禁用 Nagle 算法，确保游戏数据即时发送
                clientSocket.setTcpNoDelay(true);

                System.out.println("New Connection: " + clientSocket.getInetAddress());

                // 为每个客户端启动一个独立线程连接连接
                ClientHandler handler = new ClientHandler(clientSocket, roomManager);
                new Thread(handler).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}