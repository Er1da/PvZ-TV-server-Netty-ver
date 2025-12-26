package org.marshive.old;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class TestClientFrame extends JFrame {

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;

    private final JTextField tfHost = new JTextField("127.0.0.1");
    private final JTextField tfPort = new JTextField("8889");
    private final JButton btnConnect = new JButton("连接");
    private final JButton btnDisconnect = new JButton("断开");

    private final JTextField tfRoomName = new JTextField("MyRoom");
    private final JButton btnCreateOrExit = new JButton("创建房间");
    private final JButton btnJoinOrLeave = new JButton("加入选中房间");
    private final JButton btnStart = new JButton("开始游戏");

    private final JTextField tfRelaySend = new JTextField("hello relay");
    private final JButton btnRelaySend = new JButton("发送(进入relay后验证)");

    private final JTextArea taLog = new JTextArea();
    private final DefaultListModel<RoomItem> roomModel = new DefaultListModel<>();
    private final JList<RoomItem> roomList = new JList<>(roomModel);

    private volatile boolean inRelay = false;
    private volatile boolean hosting = false;
    private volatile boolean joined  = false;

    private volatile int hostedRoomId = 0;
    private volatile boolean hostHasGuest = false;

    private final Timer autoQueryTimer;

    public TestClientFrame() {
        super("PvZ Test Client (Binary Protocol)");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(920, 560);
        setLocationRelativeTo(null);

        taLog.setEditable(false);
        taLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel top = new JPanel(new GridLayout(1, 6, 8, 0));
        top.add(new JLabel("Host:"));
        top.add(tfHost);
        top.add(new JLabel("Port:"));
        top.add(tfPort);
        top.add(btnConnect);
        top.add(btnDisconnect);

        JPanel midLeft = new JPanel(new BorderLayout(6, 6));
        midLeft.add(new JLabel("房间列表（选中后加入）"), BorderLayout.NORTH);
        midLeft.add(new JScrollPane(roomList), BorderLayout.CENTER);

        JPanel midRight = new JPanel(new BorderLayout(6, 6));
        midRight.add(new JLabel("日志"), BorderLayout.NORTH);
        midRight.add(new JScrollPane(taLog), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, midLeft, midRight);
        split.setResizeWeight(0.35);

        JPanel row1 = new JPanel(new GridLayout(1, 2, 8, 0));
        row1.add(labeledPanel("房间名", tfRoomName, btnCreateOrExit));
        row1.add(wrap(btnJoinOrLeave));

        JPanel row2 = new JPanel(new GridLayout(1, 2, 8, 0));
        row2.add(wrap(btnStart));
        row2.add(new JPanel());

        JPanel row3 = new JPanel(new BorderLayout(8, 0));
        row3.add(new JLabel("Relay发送:"), BorderLayout.WEST);
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
            // 只有“空闲”才自动刷新：未创建、未加入、未进relay
            if (isConnected() && !hosting && !joined && !inRelay) {
                sendQuery();
            }
        });
        autoQueryTimer.setRepeats(true);

        bind();
        refreshButtons();
    }

    private void bind() {
        btnConnect.addActionListener(e -> connect());
        btnDisconnect.addActionListener(e -> disconnect("user"));

        btnCreateOrExit.addActionListener(e -> {
            if (!isConnected() || inRelay) return;

            if (!hosting) {
                sendCreate();
            } else {
                // 退出房间但不断线：EXIT_ROOM(0x06)
                sendOne((byte)0x06, "EXIT_ROOM");
            }
        });

        btnJoinOrLeave.addActionListener(e -> {
            if (!isConnected() || inRelay) return;

            if (!joined) {
                sendJoinSelected();
            } else {
                // 离开房间但不断线：LEAVE_ROOM(0x07)
                sendOne((byte)0x07, "LEAVE_ROOM");
            }
        });

        btnStart.addActionListener(e -> {
            if (!isConnected() || inRelay) return;
            sendOne((byte)0x05, "START");
        });

        btnRelaySend.addActionListener(e -> sendRelayText());
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
        int port = Integer.parseInt(tfPort.getText().trim());

        try {
            socket = new Socket(hostStr, port);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());

            inRelay = false;
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

            sendQuery();
            autoQueryTimer.start();

        } catch (Exception ex) {
            log("Connect failed: " + ex.getMessage());
            disconnect("connect fail");
        }
    }

    private void disconnect(String why) {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; in = null; out = null;

        inRelay = false;
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

        if (!c) roomModel.clear();
    }

    private void refreshButtons() {
        btnCreateOrExit.setText(hosting ? "退出房间" : "创建房间");
        btnJoinOrLeave.setText(joined ? "离开房间" : "加入选中房间");

        if (!isConnected()) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(false);
            btnStart.setEnabled(false);
            return;
        }

        if (inRelay) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(false);
            btnStart.setEnabled(false);
            return;
        }

        if (hosting) {
            btnCreateOrExit.setEnabled(true);
            btnJoinOrLeave.setEnabled(false);

            // ✅ 开始游戏按钮：仅在 host 且有人加入时启用
            btnStart.setEnabled(hostHasGuest);
            return;
        }

        if (joined) {
            btnCreateOrExit.setEnabled(false);
            btnJoinOrLeave.setEnabled(true);
            btnStart.setEnabled(false);
            return;
        }

        // 空闲
        btnCreateOrExit.setEnabled(true);
        btnJoinOrLeave.setEnabled(true);
        btnStart.setEnabled(false); // 空闲时不允许 start
    }

    private void sendQuery() { sendOne((byte)0x02, "QUERY"); }

    private void sendCreate() {
        String name = tfRoomName.getText();
        if (name == null) name = "";
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length > 255) { log("Room name too long"); return; }

        try {
            out.write(0x01);
            out.write(nb.length);
            out.write(nb);
            out.flush();
            log(">> CREATE '" + name + "'");
        } catch (IOException e) {
            log("Send CREATE failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void sendJoinSelected() {
        RoomItem it = roomList.getSelectedValue();
        if (it == null) { log("Select a room first"); return; }

        try {
            out.write(0x03);
            Proto.writeIntBE(out, it.roomId);
            out.flush();
            log(">> JOIN roomId=" + it.roomId);
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

    private void sendRelayText() {
        if (!isConnected()) return;
        String s = tfRelaySend.getText();
        if (s == null) s = "";
        byte[] data = (s + "\n").getBytes(StandardCharsets.UTF_8);

        try {
            out.write(data);
            out.flush();
            log(">> RELAY_SEND " + data.length + " bytes");
        } catch (IOException e) {
            log("Relay send failed: " + e.getMessage());
            disconnect("send error");
        }
    }

    private void readerLoop() {
        try {
            while (isConnected()) {
                if (!inRelay) {
                    int t = in.read();
                    if (t < 0) break;

                    int hi = in.read(), lo = in.read();
                    if ((hi | lo) < 0) break;

                    int len = (hi << 8) | lo;
                    byte[] payload = new byte[len];
                    Proto.readFully(in, payload);

                    handleResp((byte)t, payload);
                } else {
                    byte[] buf = new byte[4096];
                    int n = in.read(buf);
                    if (n < 0) break;
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                    log("<< RELAY_DATA: " + s.replace("\r", "\\r").replace("\n", "\\n"));
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
            log("<< ROOM_CREATED id=" + id);

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
                // 保留选中项
                RoomItem selected = roomList.getSelectedValue();
                int selectedId = (selected != null) ? selected.roomId : -1;

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
            log("<< JOIN_RESULT ok=" + ok + " roomId=" + id);

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
            log("<< GUEST_JOINED roomId=" + id);

            if (hosting && id == hostedRoomId) {
                hostHasGuest = true; // ✅ 有人加入，允许开始
                SwingUtilities.invokeLater(this::refreshButtons);
            }
            return;
        }

        if (type == RespType.GUEST_LEFT.code) {
            int id = bytesToInt(payload, 0);
            log("<< GUEST_LEFT roomId=" + id);

            if (hosting && id == hostedRoomId) {
                hostHasGuest = false; // ✅ 人走了，禁用开始
                SwingUtilities.invokeLater(this::refreshButtons);
            }
            return;
        }

        if (type == RespType.ROOM_EXITED.code) {
            log("<< ROOM_EXITED");

            // 退出/离开成功后回到空闲，并恢复自动刷新
            inRelay = false;
            hosting = false;
            joined = false;
            hostedRoomId = 0;
            hostHasGuest = false;

            autoQueryTimer.start();
            sendQuery();

            SwingUtilities.invokeLater(this::refreshButtons);
            return;
        }

        if (type == RespType.RELAY_BEGIN.code) {
            log("<< RELAY_BEGIN");
            inRelay = true;
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

    private static int bytesToInt(byte[] b, int off) {
        if (b.length < off + 4) return 0;
        return ((b[off] & 0xFF) << 24) |
                ((b[off + 1] & 0xFF) << 16) |
                ((b[off + 2] & 0xFF) << 8) |
                (b[off + 3] & 0xFF);
    }

    private static ArrayList<RoomItem> parseRoomList(byte[] p) {
        ArrayList<RoomItem> list = new ArrayList<>();
        if (p.length < 1) return list;
        int count = p[0] & 0xFF;
        int off = 1;

        for (int i = 0; i < count; i++) {
            if (off + 6 > p.length) break;
            int id = ((p[off] & 0xFF) << 24) | ((p[off+1] & 0xFF) << 16) | ((p[off+2] & 0xFF) << 8) | (p[off+3] & 0xFF);
            off += 4;
            int flags = p[off++] & 0xFF;
            int nameLen = p[off++] & 0xFF;
            if (off + nameLen > p.length) break;
            String name = new String(p, off, nameLen, StandardCharsets.UTF_8);
            off += nameLen;

            boolean full = (flags & 1) != 0;
            boolean gaming = (flags & 2) != 0;
            list.add(new RoomItem(id, name, full, gaming));
        }
        return list;
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            taLog.append(s + "\n");
            taLog.setCaretPosition(taLog.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TestClientFrame().setVisible(true));
    }

    static class RoomItem {
        final int roomId;
        final String name;
        final boolean full;
        final boolean gaming;

        RoomItem(int roomId, String name, boolean full, boolean gaming) {
            this.roomId = roomId;
            this.name = name;
            this.full = full;
            this.gaming = gaming;
        }

        @Override public String toString() {
            String s = name + " (id=" + roomId + ")";
            if (gaming) s += " [GAMING]";
            else if (full) s += " [FULL]";
            return s;
        }
    }
}
