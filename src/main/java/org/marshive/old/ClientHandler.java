package org.marshive.old;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * 处理单个客户端连接的请求，承担以下责任
 * <ul>
 *     <li>处理 I/O 请求</li>
 *     <li>响应式解析命令</li>
 *     <li>在游戏开始后，进行数据转发</li>
 * </ul>
 */
public class ClientHandler implements Runnable {

    private static final byte ERR_BAD_REQ   = 1;
    private static final byte ERR_NOT_FOUND = 2;
    private static final byte ERR_FULL      = 3;
    private static final byte ERR_NOT_HOST  = 4;
    private static final byte ERR_NOT_READY = 5;

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
            // 1. 初始化 I/O 流
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            // lobby 阶段：超时轮询，避免 guest 卡死无法切换 relay
            socket.setSoTimeout(300);

            // 2. 主循环：处理请求
            while (running) {
                // 2.1 如果房间已满且游戏开始，跳出循环进入转发阶段
                if (currentRoom != null && currentRoom.isGaming() && currentRoom.isFull()) {
                    break;
                }

                // 2.2 读取请求类型
                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException ste) {
                    continue;
                }

                // 2.3 处理断线
                if (b < 0) throw new EOFException("Client disconnected");
                MsgType type = MsgType.fromByte((byte) b);
                if (type == null) {
                    sendError(ERR_BAD_REQ);
                    continue;
                }

                // 2.4 处理命令
                handleCommand(type);
            }

            // 3. 转发阶段：双方进入数据转发模式
            if (currentRoom != null && currentRoom.isGaming() && currentRoom.isFull()) {
                socket.setSoTimeout(0);
                Proto.sendFrame(out, RespType.RELAY_BEGIN.code, null);
                forwardLoop();
            }

        } catch (IOException e) {
            System.out.println("Connection closed: " + e.getMessage());
        } finally {
            // 4. 清理连接资源
            cleanupOnDisconnect();
        }
    }

    private void handleCommand(MsgType type) throws IOException {
        RoomManager rm = RoomManager.getInstance();

        switch (type) {
            case CREATE: {
                int nameLen = Proto.readU8(in);
                byte[] nb = new byte[nameLen];
                Proto.readFully(in, nb);
                String roomName = new String(nb, StandardCharsets.UTF_8);

                currentRoom = rm.createRoom(roomName, this);
                isHost = true;

                byte[] payload = new byte[4];
                writeIntTo(payload, 0, currentRoom.getId());
                Proto.sendFrame(out, RespType.ROOM_CREATED.code, payload);
                break;
            }

            case QUERY: {
                byte[] payload = buildRoomListPayload(rm);
                Proto.sendFrame(out, RespType.ROOM_LIST.code, payload);
                break;
            }

            case JOIN: {
                // 1. 数据获取和验证
                int roomId = Proto.readIntBE(in);

                Room r = rm.getRoom(roomId);
                if (r == null) { sendJoinResult(false, 0); return; }
                if (r.isGaming()) { sendError(ERR_NOT_READY); return; }
                if (r.isFull()) { sendError(ERR_FULL); return; }

                // 2. 尝试加入房间
                boolean ok = rm.joinRoom(roomId, this);
                if (!ok) { sendJoinResult(false, 0); return; }

                // 3. 加入成功，更新状态并通知双方
                currentRoom = rm.getRoom(roomId);
                isHost = false;

                sendJoinResult(true, roomId);

                // 推送给 host：guest joined
                ClientHandler host = currentRoom.getHost();
                if (host != null) {
                    byte[] p = new byte[4];
                    writeIntTo(p, 0, roomId);
                    host.sendToClient(RespType.GUEST_JOINED.code, p);
                }
                break;
            }

            case START: {
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (!currentRoom.isFull()) { sendError(ERR_NOT_READY); return; }

                currentRoom.setGaming(true);

                // 双方都推 RELAY_BEGIN
                Proto.sendFrame(out, RespType.RELAY_BEGIN.code, null);
                ClientHandler guest = currentRoom.getGuest();
                if (guest != null) guest.sendToClient(RespType.RELAY_BEGIN.code, null);
                break;
            }

            case EXIT_ROOM: {
                // host 退出房间但不断线
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (currentRoom.isGaming()) { sendError(ERR_NOT_READY); return; }

                int roomId = currentRoom.getId();
                ClientHandler guest = currentRoom.getGuest();

                // 通知 guest：房间不存在/被退出（这里用 ROOM_EXITED 简化）
                if (guest != null) {
                    guest.sendToClient(RespType.ROOM_EXITED.code, null);
                    // guest 侧的 currentRoom 需要靠客户端收到后自行清状态；
                    // 服务端这里只负责不关 socket。
                    guest.currentRoom = null;
                    guest.isHost = false;
                }

                rm.removeRoom(roomId);

                currentRoom = null;
                isHost = false;

                Proto.sendFrame(out, RespType.ROOM_EXITED.code, null);
                break;
            }

            case LEAVE_ROOM: {
                // guest 离开房间但不断线
                if (isHost || currentRoom == null) { sendError(ERR_BAD_REQ); return; }
                if (currentRoom.isGaming()) { sendError(ERR_NOT_READY); return; }

                int roomId = currentRoom.getId();
                Room r = rm.getRoom(roomId);
                if (r == null) { sendError(ERR_NOT_FOUND); return; }

                boolean ok = rm.leaveAsGuest(roomId, this);
                if (!ok) { sendError(ERR_BAD_REQ); return; }

                // 推送给 host：guest left
                ClientHandler host = r.getHost();
                if (host != null) {
                    byte[] p = new byte[4];
                    writeIntTo(p, 0, roomId);
                    host.sendToClient(RespType.GUEST_LEFT.code, p);
                }

                currentRoom = null;
                isHost = false;

                Proto.sendFrame(out, RespType.ROOM_EXITED.code, null);
                break;
            }

            case LEAVE:
                // 仍保留：客户端真的要断开连接
                throw new IOException("User left (disconnect)");
        }
    }

    private void forwardLoop() throws IOException {
        ClientHandler opponent = isHost ? currentRoom.getGuest() : currentRoom.getHost();
        if (opponent == null) return;

        OutputStream oppOut = opponent.socket.getOutputStream();
        byte[] buf = new byte[4096];
        int n;

        // 核心逻辑：转发数据
        while ((n = socket.getInputStream().read(buf)) != -1) {
            oppOut.write(buf, 0, n);
            oppOut.flush();
        }
    }

    private void sendJoinResult(boolean ok, int roomId) throws IOException {
        byte[] payload = new byte[1 + 4];
        payload[0] = (byte) (ok ? 1 : 0);
        writeIntTo(payload, 1, roomId);
        Proto.sendFrame(out, RespType.JOIN_RESULT.code, payload);
    }

    private void sendError(byte errCode) throws IOException {
        Proto.sendFrame(out, RespType.ERROR.code, new byte[]{errCode});
    }

    public void sendToClient(byte respType, byte[] payload) throws IOException {
        synchronized (this) {
            Proto.sendFrame(this.out, respType, payload);
        }
    }

    private byte[] buildRoomListPayload(RoomManager rm) {
        // payload: [count:1] + count*([roomId:4][flags:1][nameLen:1][nameBytes])
        java.util.ArrayList<Room> list = new java.util.ArrayList<>();
        for (Room r : rm.allRooms()) {
            if (list.size() >= 255) break;
            list.add(r);
        }

        int total = 1;
        for (Room r : list) {
            byte[] nb = r.getName().getBytes(StandardCharsets.UTF_8);
            int nameLen = Math.min(255, nb.length);
            total += 4 + 1 + 1 + nameLen;
        }

        byte[] p = new byte[total];
        p[0] = (byte) list.size();
        int off = 1;

        for (Room r : list) {
            writeIntTo(p, off, r.getId()); off += 4;

            int flags = 0;
            if (r.isFull()) flags |= 1;
            if (r.isGaming()) flags |= 2;
            p[off++] = (byte) flags;

            byte[] nb = r.getName().getBytes(StandardCharsets.UTF_8);
            int nameLen = Math.min(255, nb.length);
            p[off++] = (byte) nameLen;
            System.arraycopy(nb, 0, p, off, nameLen);
            off += nameLen;
        }

        return p;
    }

    private static void writeIntTo(byte[] b, int off, int v) {
        b[off]     = (byte)((v >>> 24) & 0xFF);
        b[off + 1] = (byte)((v >>> 16) & 0xFF);
        b[off + 2] = (byte)((v >>> 8) & 0xFF);
        b[off + 3] = (byte)(v & 0xFF);
    }

    /*
        forwardLoop 调用结束后，无法正常回到循环，逻辑上双方连接资源应该都被清理，且不需要保留房间
     */
    private void cleanupOnDisconnect() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}

        // 如果断线时还是 host 且房间存在，就移除房间；否则不动
        if (currentRoom != null && isHost) {
            RoomManager.getInstance().removeRoom(currentRoom.getId());
        }
    }
}
