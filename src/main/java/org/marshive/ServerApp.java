package org.marshive;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * @author Qhbee
 */
public class ServerApp {

    private static final int PORT = 8888;

    // 自定义最大在线人数
    private static final int MAX_PLAYERS = 500 * 2;

    public static void main(String[] args) {
        System.out.println(">>> Game Server Started on Port: " + PORT);

        // ================= 自定义线程池参数 =================
        // 长连接场景: 核心线程数 = 最大线程数 = 最大用户数
        // 多余线程存活时间 (0ms) - 因为 Core=Max，这个参数无效
        // 任务队列选 SynchronousQueue，容量为 0。如果所有线程都在忙，新任务不会暂存，而是直接触发“拒绝策略”，符合游戏“满员”的逻辑。
        // 拒绝策略 (AbortPolicy 默认抛异常)
        ThreadPoolExecutor threadPool = new ThreadPoolExecutor(
                MAX_PLAYERS,
                MAX_PLAYERS,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();

                // 核心配置：开启 TCP_NODELAY，禁用 Nagle 算法，确保游戏数据即时发送
                clientSocket.setTcpNoDelay(true);

                System.out.println("New Connection: " + clientSocket.getInetAddress()
                        + " (Active: " + threadPool.getActiveCount() + "/" + MAX_PLAYERS + ")");

                // 封装任务
                ClientHandler handler = new ClientHandler(clientSocket);

                try {
                    // 尝试提交给线程池
                    threadPool.execute(handler);

                } catch (RejectedExecutionException e) {
                    // === 处理满员逻辑 ===
                    System.out.println("Refused Connection: Server Full! " + clientSocket.getInetAddress());

                    // TODO: 也可以在这里给客户端发一个 "SERVER_FULL" 的字节码再关闭
                    // try { clientSocket.getOutputStream().write(SOME_FULL_CODE); } catch...

                    clientSocket.close(); // 必须关闭，否则客户端会一直挂起
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}