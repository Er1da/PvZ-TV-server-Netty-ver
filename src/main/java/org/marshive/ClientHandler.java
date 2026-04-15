package org.marshive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClientHandler implements Runnable {
    private static final int NETPLAY_VERSION = 3154;

    private static final byte ERR_BAD_REQ   = 1;
    private static final byte ERR_NOT_FOUND = 2;
    private static final byte ERR_FULL      = 3;
    private static final byte ERR_NOT_HOST  = 4;
    private static final byte ERR_NOT_READY = 5;

    private static final int P2P_FALLBACK_MS = 3000;
    private static final ScheduledExecutorService P2P_FALLBACK_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "p2p-fallback");
                t.setDaemon(true);
                return t;
            });

    private final Socket socket;
    private final RoomManager rm;
    private final int shardId;

    private InputStream in;
    private OutputStream out;

    private Room currentRoom;
    private boolean isHost = false;
    private volatile boolean running = true;

    private volatile int natPort = -1;
    private int protocolVersion = NETPLAY_VERSION;
    private String playerName = "";

    public ClientHandler(Socket socket, RoomManager rm, int shardId) {
        this.socket = socket;
        this.rm = rm;
        this.shardId = shardId;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            socket.setSoTimeout(300);

            while (running) {
                if (currentRoom != null
                        && currentRoom.isGaming()
                        && currentRoom.isFull()
                        && currentRoom.isRelayMode()) {
                    break;
                }

                int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException ste) {
                    continue;
                }

                if (b < 0) throw new EOFException("Client disconnected");
                MsgType type = MsgType.fromByte((byte) b);
                if (type == null) {
                    sendError(ERR_BAD_REQ);
                    continue;
                }

                handleCommand(type);
            }

            if (currentRoom != null
                    && currentRoom.isGaming()
                    && currentRoom.isFull()
                    && currentRoom.isRelayMode()) {
                socket.setSoTimeout(0);
                forwardLoop();
            }

        } catch (IOException e) {
            System.out.println("[shard=" + shardId + "] Connection closed: " + e.getMessage());
        } finally {
            cleanupOnDisconnect();
        }
    }

    private void handleCommand(MsgType type) throws IOException {
        switch (type) {
            case CREATE: {
                int nameLen = Proto.readU8(in);
                byte[] nb = new byte[nameLen];
                Proto.readFully(in, nb);
                String roomName = new String(nb, StandardCharsets.UTF_8);
                int clientVersion = Proto.readIntBE(in);

                protocolVersion = clientVersion;
                playerName = roomName;
                currentRoom = rm.createRoom(roomName, this, clientVersion);
                isHost = true;

                byte[] payload = new byte[8];
                writeIntTo(payload, 0, currentRoom.getId());
                writeIntTo(payload, 4, currentRoom.getProtocolVersion());
                Proto.sendFrame(out, RespType.ROOM_CREATED.code, payload);
                break;
            }

            case QUERY: {
                byte[] payload = buildRoomListPayload(rm);
                Proto.sendFrame(out, RespType.ROOM_LIST.code, payload);
                break;
            }

            case JOIN: {
                int roomId = Proto.readIntBE(in);
                int clientVersion = Proto.readIntBE(in);
                int nameLen = Proto.readU8(in);
                byte[] nb = new byte[nameLen];
                Proto.readFully(in, nb);
                String guestName = new String(nb, StandardCharsets.UTF_8);

                Room r = rm.getRoom(roomId);
                if (r == null) { sendJoinResult(false, 0, 0); return; }
                if (r.isGaming()) { sendError(ERR_NOT_READY); return; }
                if (r.isFull()) { sendError(ERR_FULL); return; }
                if (r.getProtocolVersion() != clientVersion) {
                    sendJoinResult(false, roomId, r.getProtocolVersion());
                    return;
                }

                boolean ok = rm.joinRoom(roomId, this);
                if (!ok) { sendJoinResult(false, 0, r.getProtocolVersion()); return; }

                currentRoom = rm.getRoom(roomId);
                isHost = false;
                protocolVersion = clientVersion;
                playerName = guestName;

                sendJoinResult(true, roomId, r.getProtocolVersion(), r.getName());

                ClientHandler host = currentRoom.getHost();
                if (host != null) {
                    byte[] guestNameBytes = guestName.getBytes(StandardCharsets.UTF_8);
                    int guestNameLen = Math.min(255, guestNameBytes.length);
                    byte[] p = new byte[4 + 1 + guestNameLen];
                    writeIntTo(p, 0, roomId);
                    p[4] = (byte) guestNameLen;
                    System.arraycopy(guestNameBytes, 0, p, 5, guestNameLen);
                    host.sendToClient(RespType.GUEST_JOINED.code, p);
                }
                break;
            }

            case START: {
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (!currentRoom.isFull()) { sendError(ERR_NOT_READY); return; }

                currentRoom.setGaming(true);

                boolean p2pStarted = tryBeginP2PNegotiation(currentRoom);
                if (!p2pStarted) {
                    beginRelayFallback(currentRoom);
                }
                break;
            }

            case EXIT_ROOM: {
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (currentRoom.isGaming()) { sendError(ERR_NOT_READY); return; }

                int roomId = currentRoom.getId();
                ClientHandler guest = currentRoom.getGuest();

                if (guest != null) {
                    guest.sendToClient(RespType.ROOM_EXITED.code, null);
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
                if (isHost || currentRoom == null) { sendError(ERR_BAD_REQ); return; }
                if (currentRoom.isGaming()) { sendError(ERR_NOT_READY); return; }

                int roomId = currentRoom.getId();
                Room r = rm.getRoom(roomId);
                if (r == null) { sendError(ERR_NOT_FOUND); return; }

                boolean ok = rm.leaveAsGuest(roomId, this);
                if (!ok) { sendError(ERR_BAD_REQ); return; }

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

            case NAT_PORT: {
                int p = Proto.readU16BE(in);
                if (p <= 0) { sendError(ERR_BAD_REQ); return; }
                natPort = p;

                byte[] payload = new byte[2];
                writeU16To(payload, 0, p);
                Proto.sendFrame(out, RespType.P2P_READY.code, payload);
                break;
            }

            case P2P_OK: {
                if (currentRoom == null || !currentRoom.isGaming()) { sendError(ERR_BAD_REQ); return; }
                finishP2P(currentRoom);
                break;
            }

            case P2P_FAIL: {
                if (currentRoom == null || !currentRoom.isGaming()) { sendError(ERR_BAD_REQ); return; }
                beginRelayFallback(currentRoom);
                break;
            }

            case LEAVE:
                throw new IOException("User left (disconnect)");
        }
    }

    private boolean tryBeginP2PNegotiation(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) return false;
        if (host.natPort <= 0 || guest.natPort <= 0) return false;

        byte[] toHost = buildP2pInfoPayload(room.getId(), guest);
        byte[] toGuest = buildP2pInfoPayload(room.getId(), host);

        synchronized (room) {
            room.setP2pNegotiating(true);
            room.setP2pEstablished(false);
            room.setRelayMode(false);
        }

        try {
            host.sendToClient(RespType.P2P_INFO.code, toHost);
            guest.sendToClient(RespType.P2P_INFO.code, toGuest);
        } catch (IOException e) {
            synchronized (room) {
                room.setP2pNegotiating(false);
            }
            return false;
        }

        P2P_FALLBACK_SCHEDULER.schedule(() -> beginRelayFallback(room), P2P_FALLBACK_MS, TimeUnit.MILLISECONDS);
        return true;
    }

    private void finishP2P(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) {
            beginRelayFallback(room);
            return;
        }

        synchronized (room) {
            if (room.isRelayMode() || room.isP2pEstablished()) return;
            room.setP2pNegotiating(false);
            room.setP2pEstablished(true);
            room.setRelayMode(false);
        }

        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] P2P (p2p established)");

        try {
            host.sendToClient(RespType.P2P_DONE.code, null);
            guest.sendToClient(RespType.P2P_DONE.code, null);
        } catch (IOException ignored) {
            beginRelayFallback(room);
        }
    }

    private void beginRelayFallback(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) return;

        synchronized (room) {
            if (room.isRelayMode() || room.isP2pEstablished()) return;
            room.setP2pNegotiating(false);
            room.setRelayMode(true);
        }

        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] RELAY (relay fallback)");

        try {
            host.sendToClient(RespType.RELAY_BEGIN.code, null);
            guest.sendToClient(RespType.RELAY_BEGIN.code, null);
        } catch (IOException ignored) {
        }
    }

    private byte[] buildP2pInfoPayload(int roomId, ClientHandler peer) {
        String ip = peer.socket.getInetAddress().getHostAddress();
        if (ip == null || ip.isEmpty()) ip = "0.0.0.0";
        byte[] ipBytes = ip.getBytes(StandardCharsets.UTF_8);
        int ipLen = Math.min(255, ipBytes.length);

        byte[] p = new byte[4 + 1 + ipLen + 2 + 1];
        int off = 0;

        writeIntTo(p, off, roomId);
        off += 4;

        p[off++] = (byte) ipLen;
        System.arraycopy(ipBytes, 0, p, off, ipLen);
        off += ipLen;

        writeU16To(p, off, peer.natPort);
        off += 2;

        int timeoutSec = Math.max(1, P2P_FALLBACK_MS / 1000);
        p[off] = (byte) timeoutSec;

        return p;
    }

    private void forwardLoop() throws IOException {
        ClientHandler opponent = isHost ? currentRoom.getGuest() : currentRoom.getHost();
        if (opponent == null) return;

        OutputStream oppOut = opponent.socket.getOutputStream();

        byte[] buf = new byte[4096];
        int n;
        InputStream rawIn = socket.getInputStream();

        while ((n = rawIn.read(buf)) != -1) {
            oppOut.write(buf, 0, n);
            oppOut.flush();
        }
    }

    private void sendJoinResult(boolean ok, int roomId, int roomVersion) throws IOException {
        sendJoinResult(ok, roomId, roomVersion, "");
    }

    private void sendJoinResult(boolean ok, int roomId, int roomVersion, String hostName) throws IOException {
        byte[] hostNameBytes = hostName == null ? new byte[0] : hostName.getBytes(StandardCharsets.UTF_8);
        int hostNameLen = Math.min(255, hostNameBytes.length);
        byte[] payload = new byte[1 + 4 + 4 + 1 + hostNameLen];
        payload[0] = (byte) (ok ? 1 : 0);
        writeIntTo(payload, 1, roomId);
        writeIntTo(payload, 5, roomVersion);
        payload[9] = (byte) hostNameLen;
        if (hostNameLen > 0) {
            System.arraycopy(hostNameBytes, 0, payload, 10, hostNameLen);
        }
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
        ArrayList<Room> list = new ArrayList<>();

        for (Room r : rm.allRooms()) {
            if (r.isGaming()) continue;
            if (list.size() >= 255) break;
            list.add(r);
        }

        int total = 1;
        for (Room r : list) {
            byte[] nb = r.getName().getBytes(StandardCharsets.UTF_8);
            int nameLen = Math.min(255, nb.length);
            total += 4 + 1 + 4 + 1 + nameLen;
        }

        byte[] p = new byte[total];
        p[0] = (byte) list.size();
        int off = 1;

        for (Room r : list) {
            writeIntTo(p, off, r.getId()); off += 4;

            int flags = 0;
            if (r.isFull()) flags |= 1;
            p[off++] = (byte) flags;

            writeIntTo(p, off, r.getProtocolVersion()); off += 4;

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

    private static void writeU16To(byte[] b, int off, int v) {
        b[off] = (byte) ((v >>> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private void notifyGuestRoomExited(Room room) {
        ClientHandler guest = room.getGuest();
        if (guest == null) return;

        try {
            guest.sendToClient(RespType.ROOM_EXITED.code, null);
        } catch (IOException ignored) {
        }
        guest.currentRoom = null;
        guest.isHost = false;
    }

    private void notifyHostGuestLeft(Room room) {
        ClientHandler host = room.getHost();
        if (host == null) return;

        byte[] p = new byte[4];
        writeIntTo(p, 0, room.getId());
        try {
            host.sendToClient(RespType.GUEST_LEFT.code, p);
        } catch (IOException ignored) {
        }
    }

    private void cleanupOnDisconnect() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}

        Room room = currentRoom;
        currentRoom = null;

        if (room == null) {
            return;
        }

        if (isHost) {
            notifyGuestRoomExited(room);
            rm.removeRoom(room.getId());
        } else if (!room.isGaming() && room.getGuest() == this) {
            room.setGuest(null);
            notifyHostGuestLeft(room);
        }

        isHost = false;
    }
}
