package org.marshive;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public class ServerApp {
    private static final int PORT = 8889;
    private static final int MAX_PLAYERS = 500 * 2;

    public static void main(String[] args) {
        System.out.println(">>> Game Server Started on Port: " + PORT);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                MAX_PLAYERS, MAX_PLAYERS,
                0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );

        try (ServerSocket ss = new ServerSocket(PORT)) {
            while (true) {
                Socket s = ss.accept();
                s.setTcpNoDelay(true);

                ClientHandler h = new ClientHandler(s);
                try {
                    pool.execute(h);
                } catch (RejectedExecutionException e) {
                    s.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
