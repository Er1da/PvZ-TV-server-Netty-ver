package org.marshive;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public class ServerApp {

    private static final int DEFAULT_BASE_PORT = 8888;
    private static final int DEFAULT_SHARD_COUNT = 4;     // ✅ N：开多少个端口
    private static final int MAX_PLAYERS = 500 * 2;

    public static void main(String[] args) {

        // ✅ 从 args 读取（支持位置参数或 --base= / --shards=）
        int basePort = DEFAULT_BASE_PORT;
        int shardCount = DEFAULT_SHARD_COUNT;

        // 1) 先尝试解析 --base=xxxx --shards=yyyy
        for (String a : args) {
            if (a == null) continue;
            a = a.trim();
            if (a.startsWith("--base=")) {
                basePort = parseIntSafe(a.substring("--base=".length()), basePort);
            } else if (a.startsWith("--shards=")) {
                shardCount = parseIntSafe(a.substring("--shards=".length()), shardCount);
            }
        }

        // 2) 再尝试解析位置参数：args[0]=basePort args[1]=shardCount（如果存在）
        //    注意：如果用户同时用了 --base=，位置参数会覆盖它（你也可以反过来）
        if (args.length >= 1 && args[0] != null && !args[0].trim().startsWith("--")) {
            basePort = parseIntSafe(args[0].trim(), basePort);
        }
        if (args.length >= 2 && args[1] != null && !args[1].trim().startsWith("--")) {
            shardCount = parseIntSafe(args[1].trim(), shardCount);
        }

        // ✅ 合法性校验
        if (shardCount <= 0) {
            System.out.println("[FATAL] SHARD_COUNT must be > 0, got: " + shardCount);
            return;
        }
        if (basePort <= 0 || basePort > 65535) {
            System.out.println("[FATAL] BASE_PORT must be 1..65535, got: " + basePort);
            return;
        }
        int lastPort = basePort + shardCount - 1;
        if (lastPort > 65535) {
            System.out.println("[FATAL] Port range overflow: " + basePort + " ~ " + lastPort);
            return;
        }

        System.out.println(">>> Game Server Started on Ports: " +
                basePort + " ~ " + lastPort +
                " (base=" + basePort + ", shards=" + shardCount + ")");

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                MAX_PLAYERS, MAX_PLAYERS,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        // ✅ 每个端口一套 RoomManager（房间表隔离）
        final RoomManager[] rms = new RoomManager[shardCount];
        for (int i = 0; i < shardCount; i++) {
            rms[i] = new RoomManager(i); // shardId = i
        }

        for (int i = 0; i < shardCount; i++) {
            final int shardId = i;
            final int port = basePort + i;

            Thread acceptThread = new Thread(() -> {
                try (ServerSocket ss = new ServerSocket(port)) {
                    while (true) {
                        Socket s = ss.accept();
                        s.setTcpNoDelay(true);

                        ClientHandler h = new ClientHandler(s, rms[shardId], shardId);
                        try {
                            pool.execute(h);
                        } catch (RejectedExecutionException e) {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    }
                } catch (IOException e) {
                    System.out.println("[ACCEPT@" + port + "] crashed: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "accept-" + port);

            acceptThread.setDaemon(false);
            acceptThread.start();
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
