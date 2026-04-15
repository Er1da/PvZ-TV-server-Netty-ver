package org.marshive;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class TestClientFrame extends JFrame {
    private static final int NETPLAY_VERSION = 3154;

    private enum DataMode {
        LOBBY,
        RELAY,
        P2P
    }

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;

    private ServerSocket p2pListener;
    private volatile Socket p2pSocket;
    private volatile Socket pendingP2PSocket;

    private final JTextField tfHost = new JTextField("127.0.0.1");
    private final JTextField tfPort = new JTextField("8888");
    private final JTextField tfNatPort = new JTextField("50000");

    private final JButton btnConnect = new JButton("Connect");
    private final JButton btnDisconnect = new JButton("Disconnect");

    private final JTextField tfRoomName = new JTextField("MyRoom");
    private final JButton btnCreateOrExit = new JButton("Create Room");
    private final JButton btnJoinOrLeave = new JButton("Join Selected Room");
    private final JButton btnStart = new JButton("Start");

    private final JTextField tfRelaySend = new JTextField("hello");
    private final JButton btnRelaySend = new JButton("Send Data");

    private final JTextArea taLog = new JTextArea();
    private final DefaultListModel<RoomItem> roomModel = new DefaultListModel<>();
    private final JList<RoomItem> roomList = new JList<>(roomModel);

    private volatile DataMode dataMode = DataMode.LOBBY;
    private volatile boolean hosting = false;
    private volatile boolean joined = false;

    private volatile int hostedRoomId = 0;
    private volatile boolean hostHasGuest = false;

    private final Timer autoQueryTimer;

    public TestClientFrame() {
        this(null);
    }

    public TestClientFrame(LaunchOptions options) {
        super("PvZ Test Client (P2P First)");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(980, 600);
        setLocationRelativeTo(null);

        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel top = new JPanel(new GridLayout(1, 8, 8, 0));
        top.add(new JLabel("Host:"));
        top.add(tfHost);
        top.add(new JLabel("Port:"));
        top.add(tfPort);
        top.add(new JLabel("NAT Port:"));
        top.add(tfNatPort);
        top.add(btnConnect);
        top.add(btnDisconnect);

        JPanel midLeft = new JPanel(new BorderLayout(6, 6));
        midLeft.add(new JLabel("Room List"), BorderLayout.NORTH);
        midLeft.add(new JScrollPane(roomList), BorderLayout.CENTER);

        JPanel midRight = new JPanel(new BorderLayout(6, 6));
        midRight.add(new JLabel("Log"), BorderLayout.NORTH);
        midRight.add(new JScrollPane(taLog), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, midLeft, midRight);
        split.setResizeWeight(0.35);

        JPanel row1 = new JPanel(new GridLayout(1, 2, 8, 0));
        row1.add(labeledPanel("Room Name", tfRoomName, btnCreateOrExit));
        row1.add(wrap(btnJoinOrLeave));

        JPanel row2 = new JPanel(new GridLayout(1, 2, 8, 0));
        row2.add(wrap(btnStart));
        row2.add(new JPanel());

        JPanel row3 = new JPanel(new BorderLayout(8, 0));
        row3.add(new JLabel("Data:"), BorderLayout.WEST);
        row3.add(tfRelaySend, BorderLayout.CENTER);
        row3.add(btnRelaySend, BorderLayout.EAST);

        JPanel bottom = new JPanel(new GridLayout(3, 1, 8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        bottom.add(row1);
        bottom.add(row2);
        bottom.add(row3);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);

        btnDisconnect.setEnabled(false);
        setUiConnected(false);

        autoQueryTimer = new Timer(1000, e -> {
            if (isConnected() && !hosting && !joined && dataMode == DataMode.LOBBY) {
                sendQuery();
            }
        });
        autoQueryTimer.setRepeats(true);

        applyLaunchOptions(options);
        bind();
        refreshButtons();

        if (options != null && options.autoConnect) {
            SwingUtilities.invokeLater(this::connect);
        }
    }

    private void bind() {
        btnConnect.addActionListener(e -> connect());
        btnDisconnect.addActionListener(e -> disconnect("user"));

        btnCreateOrExit.addActionListener(e -> {
            if (!isConnected() || dataMode != DataMode.LOBBY) return;

            if (!hosting) {
                sendCreate();
            } else {
                sendOne((byte) 0x06, "EXIT_ROOM");
            }
        });

        btnJoinOrLeave.addActionListener(e -> {
            if (!isConnected() || dataMode != DataMode.LOBBY) return;

            if (!joined) {
                sendJoinSelected();
            } else {
                sendOne((byte) 0x07, "LEAVE_ROOM");
            }
        });

        btnStart.addActionListener(e -> {
            if (!isConnected() || dataMode != DataMode.LOBBY) return;
            sendOne((byte) 0x05, "START");
        });

        btnRelaySend.addActionListener(e -> sendDataText());
    }

    private static JPanel labeledPanel(String label, JComponent field, JComponent btn) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        p.add(btn, BorderLayout.EAST);
        return p;
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private void connect() {
        if (isConnected()) return;

        String hostStr = tfHost.getText().trim();
        int port = parseInt(tfPort.getText().trim(), 8888);
        int natPort = parseInt(tfNatPort.getText().trim(), 50000);

        try {
            socket = new Socket(hostStr, port);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            openP2PListener(natPort);

            dataMode = DataMode.LOBBY;
            hosting = false;
            joined = false;
            hostedRoomId = 0;
            hostHasGuest = false;

            log("Connected: " + hostStr + ":" + port);
            setUiConnected(true);
            refreshButtons();

            readerThread = new Thread(this::readerLoop, "reader");
            readerThread.setDaemon(true);
            readerThread.start();

            if (p2pListener != null) {
                sendNatPort(p2pListener.getLocalPort());
            }

            sendQuery();
            autoQueryTimer.start();

        } catch (Exception ex) {
            log("Connect failed: " + ex.getMessage());
            disconnect("connect fail");
        }
    }

    private void openP2PListener(int natPort) {
        closeP2PResources();
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(natPort));
            ss.setSoTimeout(200);
            p2pListener = ss;
            log("P2P listener opened on port " + natPort);
        } catch (IOException e) {
            p2pListener = null;
            log("P2P listener failed on port " + natPort + ": " + e.getMessage());
        }
    }

    private void disconnect(String why) {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        in = null;
        out = null;

        closeP2PResources();

        dataMode = DataMode.LOBBY;
        hosting = false;
        joined = false;
        hostedRoomId = 0;
        hostHasGuest = false;

        autoQueryTimer.stop();

        log("Disconnected: " + why);
        SwingUtilities.invokeLater(() -> {
            setUiConnected(false);
            refreshButtons();
        });
    }

    private void closeP2PResources() {
        try { if (p2pSocket != null) p2pSocket.close(); } catch (Exception ignored) {}
        try { if (pendingP2PSocket != null) pendingP2PSocket.close(); } catch (Exception ignored) {}
        try { if (p2pListener != null) p2pListener.close(); } catch (Exception ignored) {}
        p2pSocket = null;
        pendingP2PSocket = null;
        p2pListener = null;
    }

    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void setUiConnected(boolean c) {
        btnConnect.setEnabled(!c);
        btnDisconnect.setEnabled(c);

        btnCreateOrExit.setEnabled(c);
        btnJoinOrLeave.setEnabled(c);
        btnStart.setEnabled(c);
        btnRelaySend.setEnabled(c);

        tfNatPort.setEnabled(!c);

        if (!c) roomModel.clear();
    }

    private void refreshButtons() {
        btnCreateOrExit.setText(hosting ? "Exit Room" : "Create Room");
        btnJoinOrLeave.setText(joined ? "Leave Room" : "Join Selected Room");

        if (!isConnected()) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(false);
            btnStart.setEnabled(false);
            return;
        }

        if (dataMode != DataMode.LOBBY) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(false);
            btnStart.setEnabled(false);
            return;
        }

        if (hosting) {
            btnCreateOrExit.setEnabled(true);
            btnJoinOrLeave.setEnabled(false);
            btnStart.setEnabled(hostHasGuest);
            return;
        }

        if (joined) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(true);
            btnStart.setEnabled(false);
            return;
        }

        btnCreateOrExit.setEnabled(true);
        btnJoinOrLeave.setEnabled(true);
        btnStart.setEnabled(false);
    }

    private void sendQuery() {
        sendOne((byte) 0x02, "QUERY");
    }

    private void sendNatPort(int port) {
        try {
            out.write(0x08);
            Proto.writeU16BE(out, port);
            out.flush();
            log(">> NAT_PORT " + port);
        } catch (IOException e) {
            log("Send NAT_PORT failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void sendCreate() {
        String name = tfRoomName.getText();
        if (name == null) name = "";
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length > 255) {
            log("Room name too long");
            return;
        }

        try {
            out.write(0x01);
            out.write(nb.length);
            out.write(nb);
            Proto.writeIntBE(out, NETPLAY_VERSION);
            out.flush();
            log(">> CREATE '" + name + "' version=" + NETPLAY_VERSION);
        } catch (IOException e) {
            log("Send CREATE failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void sendJoinSelected() {
        RoomItem it = roomList.getSelectedValue();
        if (it == null) {
            log("Select a room first");
            return;
        }

        try {
            out.write(0x03);
            Proto.writeIntBE(out, it.roomId);
            Proto.writeIntBE(out, NETPLAY_VERSION);
            String playerName = tfRoomName.getText();
            if (playerName == null) playerName = "";
            byte[] nameBytes = playerName.getBytes(StandardCharsets.UTF_8);
            if (nameBytes.length > 255) {
                log("Player name too long");
                return;
            }
            out.write(nameBytes.length);
            out.write(nameBytes);
            out.flush();
            log(">> JOIN roomId=" + it.roomId + " version=" + NETPLAY_VERSION + " name=" + playerName);
        } catch (IOException e) {
            log("Send JOIN failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void sendOne(byte code, String name) {
        try {
            out.write(code);
            out.flush();
            log(">> " + name + " (0x" + String.format("%02X", code) + ")");
        } catch (IOException e) {
            log("Send " + name + " failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void sendDataText() {
        if (!isConnected()) return;
        if (dataMode == DataMode.LOBBY) {
            log("Data channel is not ready yet");
            return;
        }

        String s = tfRelaySend.getText();
        if (s == null) s = "";
        byte[] data = (s + "\n").getBytes(StandardCharsets.UTF_8);

        try {
            OutputStream dataOut = getActiveDataOutput();
            if (dataOut == null) {
                log("No active data output");
                return;
            }
            dataOut.write(data);
            dataOut.flush();
            log(">> DATA_SEND " + data.length + " bytes via " + dataMode);
        } catch (IOException e) {
            log("Data send failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private OutputStream getActiveDataOutput() throws IOException {
        if (dataMode == DataMode.RELAY) return out;
        if (dataMode == DataMode.P2P && p2pSocket != null) return p2pSocket.getOutputStream();
        return null;
    }

    private void readerLoop() {
        try {
            while (isConnected()) {
                if (dataMode == DataMode.LOBBY) {
                    int t = in.read();
                    if (t < 0) break;

                    int hi = in.read();
                    int lo = in.read();
                    if ((hi | lo) < 0) break;

                    int len = (hi << 8) | lo;
                    byte[] payload = new byte[len];
                    Proto.readFully(in, payload);

                    handleResp((byte) t, payload);
                } else if (dataMode == DataMode.RELAY) {
                    byte[] buf = new byte[4096];
                    int n = in.read(buf);
                    if (n < 0) break;
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                    log("<< RELAY_DATA: " + s.replace("\r", "\\r").replace("\n", "\\n"));
                } else {
                    Socket s = p2pSocket;
                    if (s == null) {
                        Thread.sleep(20);
                        continue;
                    }
                    InputStream p2pIn = s.getInputStream();
                    byte[] buf = new byte[4096];
                    int n = p2pIn.read(buf);
                    if (n < 0) break;
                    String txt = new String(buf, 0, n, StandardCharsets.UTF_8);
                    log("<< P2P_DATA: " + txt.replace("\r", "\\r").replace("\n", "\\n"));
                }
            }
        } catch (Exception e) {
            log("Reader stopped: " + e.getMessage());
        } finally {
            SwingUtilities.invokeLater(() -> disconnect("reader end"));
        }
    }

    private void handleResp(byte type, byte[] payload) {
        if (type == RespType.ROOM_CREATED.code) {
            int id = bytesToInt(payload, 0);
            int version = payload.length >= 8 ? bytesToInt(payload, 4) : 0;
            log("<< ROOM_CREATED id=" + id + " version=" + version);

            hosting = true;
            joined = false;
            hostedRoomId = id;
            hostHasGuest = false;

            autoQueryTimer.stop();
            SwingUtilities.invokeLater(this::refreshButtons);
            return;
        }

        if (type == RespType.ROOM_LIST.code) {
            ArrayList<RoomItem> rooms = parseRoomList(payload);
            SwingUtilities.invokeLater(() -> {
                RoomItem selected = roomList.getSelectedValue();
                int selectedId = selected != null ? selected.roomId : -1;

                roomModel.clear();
                for (RoomItem r : rooms) roomModel.addElement(r);

                if (selectedId != -1) {
                    for (int i = 0; i < roomModel.size(); i++) {
                        if (roomModel.get(i).roomId == selectedId) {
                            roomList.setSelectedIndex(i);
                            roomList.ensureIndexIsVisible(i);
                            break;
                        }
                    }
                }
            });
            return;
        }

        if (type == RespType.JOIN_RESULT.code) {
            boolean ok = payload.length >= 1 && payload[0] == 1;
            int id = payload.length >= 5 ? bytesToInt(payload, 1) : 0;
            int version = payload.length >= 9 ? bytesToInt(payload, 5) : 0;
            String hostName = "";
            if (payload.length >= 10) {
                int nameLen = payload[9] & 0xFF;
                if (10 + nameLen <= payload.length) {
                    hostName = new String(payload, 10, nameLen, StandardCharsets.UTF_8);
                }
            }
            log("<< JOIN_RESULT ok=" + ok + " roomId=" + id + " version=" + version + " hostName=" + hostName);

            if (ok) {
                joined = true;
                hosting = false;
                hostedRoomId = 0;
                hostHasGuest = false;

                autoQueryTimer.stop();
                SwingUtilities.invokeLater(this::refreshButtons);
            }
            return;
        }

        if (type == RespType.GUEST_JOINED.code) {
            int id = bytesToInt(payload, 0);
            String guestName = "";
            if (payload.length >= 5) {
                int nameLen = payload[4] & 0xFF;
                if (5 + nameLen <= payload.length) {
                    guestName = new String(payload, 5, nameLen, StandardCharsets.UTF_8);
                }
            }
            log("<< GUEST_JOINED roomId=" + id + " guestName=" + guestName);

            if (hosting && id == hostedRoomId) {
                hostHasGuest = true;
                SwingUtilities.invokeLater(this::refreshButtons);
            }
            return;
        }

        if (type == RespType.GUEST_LEFT.code) {
            int id = bytesToInt(payload, 0);
            log("<< GUEST_LEFT roomId=" + id);

            if (hosting && id == hostedRoomId) {
                hostHasGuest = false;
                SwingUtilities.invokeLater(this::refreshButtons);
            }
            return;
        }

        if (type == RespType.ROOM_EXITED.code) {
            log("<< ROOM_EXITED");

            dataMode = DataMode.LOBBY;
            hosting = false;
            joined = false;
            hostedRoomId = 0;
            hostHasGuest = false;

            autoQueryTimer.start();
            sendQuery();

            SwingUtilities.invokeLater(this::refreshButtons);
            return;
        }

        if (type == RespType.P2P_READY.code) {
            int p = payload.length >= 2 ? (((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF)) : -1;
            log("<< P2P_READY localPort=" + p);
            return;
        }

        if (type == RespType.P2P_INFO.code) {
            log("<< P2P_INFO");
            handleP2PInfo(payload);
            return;
        }

        if (type == RespType.P2P_DONE.code) {
            log("<< P2P_DONE");
            if (pendingP2PSocket != null) {
                p2pSocket = pendingP2PSocket;
                pendingP2PSocket = null;
                dataMode = DataMode.P2P;
                autoQueryTimer.stop();
                log("P2P channel active");
            } else {
                log("P2P_DONE received but no direct socket, waiting for relay fallback");
            }
            SwingUtilities.invokeLater(this::refreshButtons);
            return;
        }

        if (type == RespType.RELAY_BEGIN.code) {
            log("<< RELAY_BEGIN");
            closePendingP2POnly();
            dataMode = DataMode.RELAY;
            autoQueryTimer.stop();
            SwingUtilities.invokeLater(this::refreshButtons);
            return;
        }

        if (type == RespType.ERROR.code) {
            int ec = payload.length > 0 ? (payload[0] & 0xFF) : -1;
            log("<< ERROR code=" + ec);
            return;
        }

        log("<< UNKNOWN_RESP type=0x" + String.format("%02X", type) + " len=" + payload.length);
    }

    private void handleP2PInfo(byte[] payload) {
        if (payload.length < 8) {
            log("P2P_INFO payload too short");
            sendP2PFail();
            return;
        }

        int off = 0;
        int roomId = bytesToInt(payload, off);
        off += 4;

        int ipLen = payload[off++] & 0xFF;
        if (off + ipLen + 3 > payload.length) {
            log("P2P_INFO payload malformed");
            sendP2PFail();
            return;
        }

        String peerIp = new String(payload, off, ipLen, StandardCharsets.UTF_8);
        off += ipLen;

        int peerPort = ((payload[off] & 0xFF) << 8) | (payload[off + 1] & 0xFF);
        off += 2;

        int timeoutSec = payload[off] & 0xFF;
        if (timeoutSec <= 0) timeoutSec = 3;

        log("P2P target roomId=" + roomId + " peer=" + peerIp + ":" + peerPort + " timeout=" + timeoutSec + "s");

        final int timeoutMs = timeoutSec * 1000;
        Thread t = new Thread(() -> {
            Socket direct = tryEstablishDirect(peerIp, peerPort, timeoutMs);
            if (direct != null) {
                pendingP2PSocket = direct;
                log("P2P socket established, sending P2P_OK");
                sendP2POk();
            } else {
                log("P2P establish failed, sending P2P_FAIL");
                sendP2PFail();
            }
        }, "p2p-attempt");
        t.setDaemon(true);
        t.start();
    }

    private Socket tryEstablishDirect(String peerIp, int peerPort, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline && isConnected() && dataMode == DataMode.LOBBY) {
            if (p2pListener != null) {
                try {
                    Socket accepted = p2pListener.accept();
                    accepted.setTcpNoDelay(true);
                    log("P2P accepted from " + accepted.getRemoteSocketAddress());
                    return accepted;
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    log("P2P accept error: " + e.getMessage());
                }
            }

            Socket outbound = new Socket();
            try {
                outbound.setTcpNoDelay(true);
                outbound.connect(new InetSocketAddress(peerIp, peerPort), 250);
                log("P2P outbound connected to " + peerIp + ":" + peerPort);
                return outbound;
            } catch (IOException ignored) {
                try { outbound.close(); } catch (IOException ignored2) {}
            }

            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return null;
    }

    private void sendP2POk() {
        sendOne((byte) 0x09, "P2P_OK");
    }

    private void sendP2PFail() {
        closePendingP2POnly();
        sendOne((byte) 0x0A, "P2P_FAIL");
    }

    private void closePendingP2POnly() {
        try { if (pendingP2PSocket != null) pendingP2PSocket.close(); } catch (IOException ignored) {}
        pendingP2PSocket = null;
    }

    private static int bytesToInt(byte[] b, int off) {
        if (b.length < off + 4) return 0;
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static ArrayList<RoomItem> parseRoomList(byte[] p) {
        ArrayList<RoomItem> list = new ArrayList<>();
        if (p.length < 1) return list;
        int count = p[0] & 0xFF;
        int off = 1;

        for (int i = 0; i < count; i++) {
            if (off + 10 > p.length) break;
            int id = ((p[off] & 0xFF) << 24)
                    | ((p[off + 1] & 0xFF) << 16)
                    | ((p[off + 2] & 0xFF) << 8)
                    | (p[off + 3] & 0xFF);
            off += 4;
            int flags = p[off++] & 0xFF;
            int version = ((p[off] & 0xFF) << 24)
                    | ((p[off + 1] & 0xFF) << 16)
                    | ((p[off + 2] & 0xFF) << 8)
                    | (p[off + 3] & 0xFF);
            off += 4;
            int nameLen = p[off++] & 0xFF;
            if (off + nameLen > p.length) break;
            String name = new String(p, off, nameLen, StandardCharsets.UTF_8);
            off += nameLen;

            boolean full = (flags & 1) != 0;
            boolean gaming = (flags & 2) != 0;
            list.add(new RoomItem(id, name, full, gaming, version));
        }
        return list;
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            taLog.append(s + "\n");
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }

    private void applyLaunchOptions(LaunchOptions options) {
        if (options == null) return;
        if (options.host != null) tfHost.setText(options.host);
        if (options.port > 0) tfPort.setText(String.valueOf(options.port));
        if (options.natPort > 0) tfNatPort.setText(String.valueOf(options.natPort));
        if (options.roomName != null) tfRoomName.setText(options.roomName);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        LaunchOptions options = LaunchOptions.parse(args);
        SwingUtilities.invokeLater(() -> new TestClientFrame(options).setVisible(true));
    }

    static class LaunchOptions {
        String host;
        int port;
        int natPort;
        String roomName;
        boolean autoConnect;

        static LaunchOptions parse(String[] args) {
            LaunchOptions o = new LaunchOptions();
            if (args == null) return o;
            for (String a : args) {
                if (a == null) continue;
                if (a.startsWith("--host=")) {
                    o.host = a.substring("--host=".length());
                } else if (a.startsWith("--port=")) {
                    o.port = parseInt(a.substring("--port=".length()), 0);
                } else if (a.startsWith("--nat=")) {
                    o.natPort = parseInt(a.substring("--nat=".length()), 0);
                } else if (a.startsWith("--room=")) {
                    o.roomName = a.substring("--room=".length());
                } else if ("--autoconnect".equals(a)) {
                    o.autoConnect = true;
                }
            }
            return o;
        }
    }

    static class RoomItem {
        final int roomId;
        final String name;
        final boolean full;
        final boolean gaming;
        final int version;

        RoomItem(int roomId, String name, boolean full, boolean gaming, int version) {
            this.roomId = roomId;
            this.name = name;
            this.full = full;
            this.gaming = gaming;
            this.version = version;
        }

        @Override
        public String toString() {
            String s = name + " (id=" + roomId + ")";
            if (gaming) s += " [GAMING]";
            else if (full) s += " [FULL]";
            if (version != NETPLAY_VERSION) s += " [VER " + version + "]";
            return s;
        }
    }
}
