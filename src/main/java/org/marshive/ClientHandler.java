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
import java.util.concurrent.atomic.AtomicInteger;

public class ClientHandler implements Runnable {
    private static final int NETPLAY_VERSION = 3154;

    private static final byte ERR_BAD_REQ   = 1;
    private static final byte ERR_NOT_FOUND = 2;
    private static final byte ERR_FULL      = 3;
    private static final byte ERR_NOT_HOST  = 4;
    private static final byte ERR_NOT_READY = 5;
    private static final AtomicInteger CONN_SEQ = new AtomicInteger(1000);
    private static final AtomicInteger NEGOTIATION_SEQ = new AtomicInteger(1);

    private final int connId = CONN_SEQ.incrementAndGet();
    private static final int P2P_FALLBACK_MS = 5000;
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

    private static final ConcurrentHashMap<Integer, ClientHandler> PROBE_WAITERS = new ConcurrentHashMap<>();

    private InputStream in;
    private OutputStream out;

    private Room currentRoom;
    private boolean isHost = false;
    private volatile boolean running = true;

    private volatile int natPort = -1;
    private volatile String observedNatIp = "";
    private volatile int observedNatPort = -1;
    private volatile int probeToken = 0;
    private int protocolVersion = NETPLAY_VERSION;
    private String playerName = "";

    public ClientHandler(Socket socket, RoomManager rm, int shardId, int probePort) {
        this.socket = socket;
        this.rm = rm;
        this.shardId = shardId;
        this.probePort = probePort;
    }

    private String safePlayer() {
        return (playerName == null || playerName.isEmpty()) ? "?" : playerName;
    }

    private static String safePlayer(ClientHandler h) {
        if (h == null) return "null";
        return (h.playerName == null || h.playerName.isEmpty()) ? "?" : h.playerName;
    }

    private int safeRoomId(Room room) {
        return room == null ? -1 : room.getId();
    }

    private static String safeAddr(Socket s) {
        if (s == null) return "null";
        try {
            return String.valueOf(s.getRemoteSocketAddress());
        } catch (Exception e) {
            return "addr_err";
        }
    }

    private String selfRole(Room room) {
        if (room == null) return "no_room";
        if (room.getHost() == this) return "host";
        if (room.getGuest() == this) return "guest";
        return "outsider";
    }

    private static String peerState(String role, ClientHandler h) {
        if (h == null) return role + "{null}";
        return role + "{"
                + "conn=" + h.connId
                + ",name=" + safePlayer(h)
                + ",natPort=" + h.natPort
                + ",obs=" + h.observedNatIp + ":" + h.observedNatPort
                + ",token=" + h.probeToken
                + ",remote=" + safeAddr(h.socket)
                + "}";
    }

    private static String roomState(Room room) {
        if (room == null) return "room=null";
        return "room={"
                + "id=" + room.getId()
                + ",gaming=" + room.isGaming()
                + ",full=" + room.isFull()
                + ",p2pNegotiating=" + room.isP2pNegotiating()
                + ",p2pEstablished=" + room.isP2pEstablished()
                + ",relayMode=" + room.isRelayMode()
                + "}";
    }

    private void logState(String tag, Room room, String extra) {
        ClientHandler host = room == null ? null : room.getHost();
        ClientHandler guest = room == null ? null : room.getGuest();

        System.out.println("[" + tag + "]"
                + "[shard=" + shardId + "]"
                + "[conn=" + connId + "]"
                + "[role=" + selfRole(room) + "]"
                + "[player=" + safePlayer() + "] "
                + roomState(room) + " "
                + peerState("host", host) + " "
                + peerState("guest", guest)
                + (extra == null || extra.isEmpty() ? "" : " | " + extra));
    }

    private static String p2pFailReason(Room room) {
        if (room == null) return "room_null";

        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();

        if (host == null) return "host_null";
        if (guest == null) return "guest_null";

        if (host.natPort <= 0) return "host_natPort_missing";
        if (guest.natPort <= 0) return "guest_natPort_missing";
        if (host.observedNatPort <= 0) return "host_observedNatPort_missing";
        if (guest.observedNatPort <= 0) return "guest_observedNatPort_missing";

        return "unknown";
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
            System.out.println("[LOBBY_CONN]"
                    + "[shard=" + shardId + "]"
                    + "[conn=" + connId + "]"
                    + " remote=" + socket.getRemoteSocketAddress()
                    + " local=" + socket.getLocalSocketAddress());

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
                System.out.println("[CREATE_REQ][shard=" + shardId + "] remote=" + socket.getRemoteSocketAddress() + " nameLen=" + nameLen + " version=" + clientVersion);

                protocolVersion = clientVersion;
                playerName = roomName;
                currentRoom = rm.createRoom(roomName, this, clientVersion);
                isHost = true;
                logState("CREATE_OK", currentRoom,
                        "clientVersion=" + clientVersion
                                + ", roomName=" + roomName);

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
                System.out.println("[JOIN_REQ][shard=" + shardId + "] remote=" + socket.getRemoteSocketAddress() + " room=" + roomId + " nameLen=" + nameLen + " version=" + clientVersion);

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
                logState("JOIN_OK", currentRoom,
                        "joinRoomId=" + roomId
                                + ", guestName=" + guestName
                                + ", clientVersion=" + clientVersion);

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

                logState("START_REQ_BEFORE", currentRoom, null);

                currentRoom.setGaming(true);

                logState("START_REQ_AFTER_SET_GAMING", currentRoom, null);

                boolean p2pStarted = tryBeginP2PNegotiation(currentRoom);
                if (!p2pStarted) {
                    logState("START_REQ_P2P_NOT_STARTED", currentRoom,
                            "reason=" + p2pFailReason(currentRoom));
                    beginRelayFallback(currentRoom, "start_immediate_fail");
                } else {
                    logState("START_REQ_P2P_STARTED", currentRoom, null);
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

                int oldNatPort = natPort;
                int oldObservedNatPort = observedNatPort;
                int oldToken = probeToken;

                natPort = p;
                observedNatIp = "";
                observedNatPort = -1;
                unregisterProbeToken();
                probeToken = registerProbeToken(this);

                logState("NAT_PORT_SET", currentRoom,
                        "oldNatPort=" + oldNatPort
                                + ", newNatPort=" + natPort
                                + ", oldObservedNatPort=" + oldObservedNatPort
                                + ", oldToken=" + oldToken
                                + ", newToken=" + probeToken
                                + ", replyProbePort=" + probePort);

                byte[] payload = new byte[8];
                writeU16To(payload, 0, p);
                writeU16To(payload, 2, probePort);
                writeIntTo(payload, 4, probeToken);
                Proto.sendFrame(out, RespType.P2P_READY.code, payload);
                break;
            }

            case P2P_OK: {
                if (currentRoom == null || !currentRoom.isGaming()) { sendError(ERR_BAD_REQ); return; }
                logState("P2P_OK_RECV", currentRoom, null);
                finishP2P(currentRoom);
                break;
            }

            case P2P_FAIL: {
                if (currentRoom == null || !currentRoom.isGaming()) { sendError(ERR_BAD_REQ); return; }
                logState("P2P_FAIL_RECV", currentRoom, null);
                beginRelayFallback(currentRoom, "client_report_fail");
                break;
            }

            case LEAVE:
                throw new IOException("User left (disconnect)");
        }
    }

    private boolean tryBeginP2PNegotiation(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();

        if (host == null || guest == null) {
            logState("P2P_BEGIN_BLOCKED", room, "reason=" + p2pFailReason(room));
            return false;
        }
        if (host.natPort <= 0 || guest.natPort <= 0) {
            logState("P2P_BEGIN_BLOCKED", room, "reason=" + p2pFailReason(room));
            return false;
        }
        if (host.observedNatPort <= 0 || guest.observedNatPort <= 0) {
            logState("P2P_BEGIN_BLOCKED", room, "reason=" + p2pFailReason(room));
            return false;
        }

        final int attemptId = NEGOTIATION_SEQ.getAndIncrement();

        byte[] toHost = buildP2pInfoPayload(room.getId(), guest);
        byte[] toGuest = buildP2pInfoPayload(room.getId(), host);

        synchronized (room) {
            room.setP2pNegotiating(true);
            room.setP2pEstablished(false);
            room.setRelayMode(false);
        }

        logState("P2P_BEGIN_READY", room,
                "attempt=" + attemptId
                        + ", toHostPeer=" + guest.observedNatIp + ":" + guest.observedNatPort
                        + ", toGuestPeer=" + host.observedNatIp + ":" + host.observedNatPort
                        + ", timeoutMs=" + P2P_FALLBACK_MS);

        try {
            host.sendToClient(RespType.P2P_INFO.code, toHost);
            guest.sendToClient(RespType.P2P_INFO.code, toGuest);
        } catch (IOException e) {
            synchronized (room) {
                room.setP2pNegotiating(false);
            }
            logState("P2P_INFO_SEND_FAIL", room,
                    "attempt=" + attemptId + ", ex=" + e.getMessage());
            return false;
        }

        logState("P2P_INFO_SENT", room, "attempt=" + attemptId);

        P2P_FALLBACK_SCHEDULER.schedule(() -> {
            ClientHandler h = room.getHost();
            ClientHandler g = room.getGuest();

            String reason;
            synchronized (room) {
                if (room.isRelayMode()) {
                    reason = "already_relay";
                } else if (room.isP2pEstablished()) {
                    reason = "already_p2p";
                } else {
                    reason = "timeout_no_p2p_ok";
                }
            }

            if (!"timeout_no_p2p_ok".equals(reason)) {
                if (h != null) {
                    h.logState("P2P_FALLBACK_TIMER_SKIP", room,
                            "attempt=" + attemptId + ", reason=" + reason);
                } else if (g != null) {
                    g.logState("P2P_FALLBACK_TIMER_SKIP", room,
                            "attempt=" + attemptId + ", reason=" + reason);
                } else {
                    System.out.println("[P2P_FALLBACK_TIMER_SKIP]"
                            + " attempt=" + attemptId
                            + " reason=" + reason
                            + " roomId=" + room.getId());
                }
                return;
            }

            if (h != null) {
                h.logState("P2P_FALLBACK_TIMER_FIRE", room,
                        "attempt=" + attemptId + ", reason=" + reason);
            } else if (g != null) {
                g.logState("P2P_FALLBACK_TIMER_FIRE", room,
                        "attempt=" + attemptId + ", reason=" + reason);
            }

            beginRelayFallback(room, "timeout_attempt_" + attemptId);
        }, P2P_FALLBACK_MS, TimeUnit.MILLISECONDS);

        return true;
    }

    private void finishP2P(Room room) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) {
            beginRelayFallback(room, "finishP2P_host_or_guest_null");
            return;
        }

        logState("P2P_FINISH_ENTER", room, null);

        synchronized (room) {
            if (room.isRelayMode()) {
                logState("P2P_FINISH_SKIP", room, "reason=already_relay");
                return;
            }
            if (room.isP2pEstablished()) {
                logState("P2P_FINISH_SKIP", room, "reason=already_p2p");
                return;
            }
            room.setP2pNegotiating(false);
            room.setP2pEstablished(true);
            room.setRelayMode(false);
        }

        logState("P2P_ESTABLISHED", room, null);

        try {
            host.sendToClient(RespType.P2P_DONE.code, null);
            guest.sendToClient(RespType.P2P_DONE.code, null);
        } catch (IOException e) {
            beginRelayFallback(room, "send_p2p_done_fail");
        }
    }

    private void beginRelayFallback(Room room, String reason) {
        ClientHandler host = room.getHost();
        ClientHandler guest = room.getGuest();
        if (host == null || guest == null) {
            if (host != null) {
                host.logState("RELAY_ABORT_NO_PEER", room, "reason=" + reason);
            } else if (guest != null) {
                guest.logState("RELAY_ABORT_NO_PEER", room, "reason=" + reason);
            }
            return;
        }

        synchronized (room) {
            if (room.isRelayMode()) {
                host.logState("RELAY_SKIP_ALREADY_RELAY", room, "reason=" + reason);
                return;
            }
            if (room.isP2pEstablished()) {
                host.logState("RELAY_SKIP_ALREADY_P2P", room, "reason=" + reason);
                return;
            }
            room.setP2pNegotiating(false);
            room.setRelayMode(true);
        }

        host.logState("RELAY_BEGIN", room, "reason=" + reason);

        try {
            host.sendToClient(RespType.RELAY_BEGIN.code, null);
            guest.sendToClient(RespType.RELAY_BEGIN.code, null);
        } catch (IOException e) {
            host.logState("RELAY_SEND_FAIL", room, "reason=" + reason + ", ex=" + e.getMessage());
        }
    }

    private byte[] buildP2pInfoPayload(int roomId, ClientHandler peer) {
        String ip = peer.observedNatIp;
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

        writeU16To(p, off, peer.observedNatPort);
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

    private void updateObservedEndpoint(Socket probeSocket) {
        String oldIp = observedNatIp;
        int oldPort = observedNatPort;

        observedNatIp = probeSocket.getInetAddress().getHostAddress();
        observedNatPort = probeSocket.getPort();

        logState("P2P_PROBE_HIT", currentRoom,
                "oldObserved=" + oldIp + ":" + oldPort
                        + ", newObserved=" + observedNatIp + ":" + observedNatPort
                        + ", probeRemote=" + probeSocket.getRemoteSocketAddress()
                        + ", probeLocal=" + probeSocket.getLocalSocketAddress());
    }

    public static void acceptP2PProbe(int token, Socket probeSocket) {
        ClientHandler handler = PROBE_WAITERS.get(token);
        if (handler == null) {
            System.out.println("[P2P_PROBE_MISS]"
                    + " token=" + token
                    + " remote=" + probeSocket.getRemoteSocketAddress()
                    + " local=" + probeSocket.getLocalSocketAddress());
            return;
        }

        System.out.println("[P2P_PROBE_DISPATCH]"
                + "[shard=" + handler.shardId + "]"
                + "[conn=" + handler.connId + "]"
                + "[player=" + safePlayer(handler) + "]"
                + " token=" + token
                + " remote=" + probeSocket.getRemoteSocketAddress()
                + " local=" + probeSocket.getLocalSocketAddress());

        handler.updateObservedEndpoint(probeSocket);
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
        unregisterProbeToken();
        System.out.println("[LOBBY_CLOSE][shard=" + shardId + "] player=" + playerName + " remote=" + socket.getRemoteSocketAddress());

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
