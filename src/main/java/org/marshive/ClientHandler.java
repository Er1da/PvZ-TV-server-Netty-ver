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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
    private final int probePort;
    private final int probePort2;

    private static final ConcurrentHashMap<Integer, ClientHandler> PROBE_WAITERS = new ConcurrentHashMap<>();

    private InputStream in;
    private OutputStream out;

    private Room currentRoom;
    private boolean isHost = false;
    private volatile boolean running = true;

    private volatile int natPort = -1;
    private volatile String observedNatIp1 = "";
    private volatile int observedNatPort1 = -1;
    private volatile String observedNatIp2 = "";
    private volatile int observedNatPort2 = -1;
    private volatile int probeToken = 0;
    private int protocolVersion = NETPLAY_VERSION;
    private String playerName = "";

    public ClientHandler(Socket socket, RoomManager rm, int shardId, int probePort, int probePort2) {
        this.socket = socket;
        this.rm = rm;
        this.shardId = shardId;
        this.probePort = probePort;
        this.probePort2 = probePort2;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            socket.setSoTimeout(300);

            while (running) {
                if (isRelayDataPhaseReady(currentRoom)) {
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

            if (isRelayDataPhaseReady(currentRoom)) {
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

                if (currentRoom != null) {
                    sendError(ERR_BAD_REQ);
                    return;
                }

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
                sendRoomProbeState(currentRoom);
                break;
            }

            case START: {
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (!currentRoom.isFull()) { sendError(ERR_NOT_READY); return; }

                synchronized (currentRoom) {
                    currentRoom.setGaming(true);
                    currentRoom.setP2pNegotiating(false);
                    currentRoom.setP2pEstablished(false);
                    currentRoom.setRelayMode(false);
                    currentRoom.setRelayEpoch(0);
                    currentRoom.setHostRelayReady(false);
                    currentRoom.setGuestRelayReady(false);
                    currentRoom.setRelayDataOpen(false);
                }

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
                sendRoomProbeState(r);

                currentRoom = null;
                isHost = false;

                Proto.sendFrame(out, RespType.ROOM_EXITED.code, null);
                break;
            }

            case KICK_GUEST: {
                if (!isHost || currentRoom == null) { sendError(ERR_NOT_HOST); return; }
                if (currentRoom.isGaming()) { sendError(ERR_NOT_READY); return; }

                ClientHandler guest = currentRoom.getGuest();
                if (guest == null) { sendError(ERR_NOT_FOUND); return; }

                try {
                    guest.sendToClient(RespType.ROOM_EXITED.code, null);
                } catch (IOException ignored) {
                }
                guest.currentRoom = null;
                guest.isHost = false;
                currentRoom.setGuest(null);
                notifyHostGuestLeft(currentRoom);
                sendRoomProbeState(currentRoom);
                break;
            }

            case NAT_PORT: {
                int p = Proto.readU16BE(in);
                if (p <= 0) { sendError(ERR_BAD_REQ); return; }
                natPort = p;
                observedNatIp1 = "";
                observedNatPort1 = -1;
                observedNatIp2 = "";
                observedNatPort2 = -1;
                unregisterProbeToken();
                probeToken = registerProbeToken(this);

                byte[] payload = new byte[10];
                writeU16To(payload, 0, p);
                writeU16To(payload, 2, probePort);
                writeU16To(payload, 4, probePort2);
                writeIntTo(payload, 6, probeToken);
                Proto.sendFrame(out, RespType.P2P_READY.code, payload);
                sendRoomProbeState(currentRoom);
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

            case RELAY_READY: {
                int relayEpoch = Proto.readIntBE(in);
                if (currentRoom == null || !currentRoom.isGaming() || !currentRoom.isRelayMode()) {
                    sendError(ERR_BAD_REQ);
                    return;
                }
                markRelayReady(currentRoom, relayEpoch);
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
        if (!host.hasStableProbe() || !guest.hasStableProbe()) return false;

        byte[] toHost = buildP2pInfoPayload(room.getId(), guest);
        byte[] toGuest = buildP2pInfoPayload(room.getId(), host);

        synchronized (room) {
            room.setP2pNegotiating(true);
            room.setP2pEstablished(false);
            room.setRelayMode(false);
            room.setRelayEpoch(0);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
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
            room.setRelayEpoch(0);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
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

        int relayEpoch;
        synchronized (room) {
            if (room.isRelayMode() || room.isP2pEstablished()) return;
            room.setP2pNegotiating(false);
            room.setRelayMode(true);
            room.setP2pEstablished(false);
            room.setHostRelayReady(false);
            room.setGuestRelayReady(false);
            room.setRelayDataOpen(false);
            relayEpoch = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            room.setRelayEpoch(relayEpoch);
        }

        System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId() + "] RELAY (relay fallback)");

        byte[] relayPayload = new byte[4];
        writeIntTo(relayPayload, 0, relayEpoch);
        try {
            host.sendToClient(RespType.RELAY_BEGIN.code, relayPayload);
            guest.sendToClient(RespType.RELAY_BEGIN.code, relayPayload);
        } catch (IOException ignored) {
        }
    }

    private void markRelayReady(Room room, int relayEpoch) {
        boolean shouldOpenRelay = false;
        int openEpoch = relayEpoch;
        ClientHandler host = null;
        ClientHandler guest = null;

        synchronized (room) {
            if (!room.isRelayMode()) return;
            if (relayEpoch != room.getRelayEpoch()) {
                System.out.println("[RELAY_READY][shard=" + shardId + "][room=" + room.getId()
                        + "] stale epoch=" + relayEpoch + " expected=" + room.getRelayEpoch());
                return;
            }
            if (isHost) {
                room.setHostRelayReady(true);
            } else {
                room.setGuestRelayReady(true);
            }
            System.out.println("[RELAY_READY][shard=" + shardId + "][room=" + room.getId()
                    + "] hostReady=" + room.isHostRelayReady()
                    + " guestReady=" + room.isGuestRelayReady()
                    + " epoch=" + relayEpoch);

            if (room.isHostRelayReady() && room.isGuestRelayReady() && !room.isRelayDataOpen()) {
                room.setRelayDataOpen(true);
                shouldOpenRelay = true;
                openEpoch = room.getRelayEpoch();
                host = room.getHost();
                guest = room.getGuest();
            }
        }

        if (!shouldOpenRelay || host == null || guest == null) {
            return;
        }

        byte[] payload = new byte[4];
        writeIntTo(payload, 0, openEpoch);
        try {
            host.sendToClient(RespType.RELAY_GO.code, payload);
            guest.sendToClient(RespType.RELAY_GO.code, payload);
            System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId()
                    + "] RELAY_GO epoch=" + openEpoch);
        } catch (IOException e) {
            synchronized (room) {
                room.setRelayDataOpen(false);
            }
            System.out.println("[GAME_MODE][shard=" + shardId + "][room=" + room.getId()
                    + "] RELAY_GO send failed: " + e.getMessage());
        }
    }

    private boolean isRelayDataPhaseReady(Room room) {
        if (room == null) return false;
        synchronized (room) {
            return room.isGaming()
                    && room.isFull()
                    && room.isRelayMode()
                    && room.isRelayDataOpen();
        }
    }

    private byte[] buildP2pInfoPayload(int roomId, ClientHandler peer) {
        String ip = peer.getStableNatIp();
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

        writeU16To(p, off, peer.getStableNatPort());
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

    public boolean isProbeReady() {
        return natPort > 0 && hasStableProbe();
    }

    private static int registerProbeToken(ClientHandler handler) {
        int token;
        do {
            token = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        } while (PROBE_WAITERS.putIfAbsent(token, handler) != null);
        return token;
    }

    private void unregisterProbeToken() {
        int token = probeToken;
        if (token != 0) {
            PROBE_WAITERS.remove(token, this);
            probeToken = 0;
        }
    }

    private synchronized void updateObservedEndpoint(Socket probeSocket, int probeIndex) {
        String ip = probeSocket.getInetAddress().getHostAddress();
        int port = probeSocket.getPort();
        if (probeIndex == 2) {
            observedNatIp2 = ip;
            observedNatPort2 = port;
        } else {
            observedNatIp1 = ip;
            observedNatPort1 = port;
        }
        System.out.println("[P2P_PROBE][shard=" + shardId + "][idx=" + probeIndex + "] " + ip + ":" + port + " player=" + playerName);
        sendRoomProbeState(currentRoom);
    }

    public static void acceptP2PProbe(int token, Socket probeSocket, int probeIndex) {
        ClientHandler handler = PROBE_WAITERS.get(token);
        if (handler == null) return;
        handler.updateObservedEndpoint(probeSocket, probeIndex);
    }

    private synchronized boolean hasStableProbe() {
        if (observedNatPort1 <= 0 || observedNatPort2 <= 0) return false;
        if (observedNatIp1 == null || observedNatIp2 == null) return false;
        if (observedNatIp1.isEmpty() || observedNatIp2.isEmpty()) return false;
        return observedNatPort1 == observedNatPort2 && observedNatIp1.equals(observedNatIp2);
    }

    private synchronized String getStableNatIp() {
        return hasStableProbe() ? observedNatIp1 : "";
    }

    private synchronized int getStableNatPort() {
        return hasStableProbe() ? observedNatPort1 : -1;
    }

    private void sendRoomProbeState(Room room) {
        if (room == null) return;

        ClientHandler host;
        ClientHandler guest;
        int roomId;
        boolean hostReady;
        boolean guestReady;
        synchronized (room) {
            host = room.getHost();
            guest = room.getGuest();
            roomId = room.getId();
            hostReady = host != null && host.isProbeReady();
            guestReady = guest != null && guest.isProbeReady();
        }

        byte[] payload = new byte[6];
        writeIntTo(payload, 0, roomId);
        payload[4] = (byte) (hostReady ? 1 : 0);
        payload[5] = (byte) (guestReady ? 1 : 0);

        if (host != null) {
            try {
                host.sendToClient(RespType.ROOM_PROBE_STATE.code, payload);
            } catch (IOException ignored) {
            }
        }
        if (guest != null && guest != host) {
            try {
                guest.sendToClient(RespType.ROOM_PROBE_STATE.code, payload);
            } catch (IOException ignored) {
            }
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
            if (r.isGaming()) flags |= 2;
            ClientHandler host = r.getHost();
            ClientHandler guest = r.getGuest();
            if (host != null && host.isProbeReady()) flags |= 4;
            if (guest != null && guest.isProbeReady()) flags |= 8;
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
        unregisterProbeToken();

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
            sendRoomProbeState(room);
        }

        isHost = false;
    }
}
