package org.marshive;

public class Room {
    private final int id;
    private final String name;
    private final ClientHandler host;
    private final int protocolVersion;
    private volatile ClientHandler guest;
    private volatile boolean gaming = false;
    private volatile boolean p2pNegotiating = false;
    private volatile boolean p2pEstablished = false;
    private volatile boolean relayMode = false;
    private volatile int relayEpoch = 0;
    private volatile boolean hostRelayReady = false;
    private volatile boolean guestRelayReady = false;
    private volatile boolean relayDataOpen = false;

    public Room(int id, String name, ClientHandler host, int protocolVersion) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.protocolVersion = protocolVersion;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public ClientHandler getHost() { return host; }
    public int getProtocolVersion() { return protocolVersion; }
    public ClientHandler getGuest() { return guest; }
    public void setGuest(ClientHandler g) { this.guest = g; }

    public boolean isGaming() { return gaming; }
    public void setGaming(boolean v) { this.gaming = v; }

    public boolean isFull() { return host != null && guest != null; }

    public boolean isP2pNegotiating() { return p2pNegotiating; }
    public void setP2pNegotiating(boolean v) { this.p2pNegotiating = v; }

    public boolean isP2pEstablished() { return p2pEstablished; }
    public void setP2pEstablished(boolean v) { this.p2pEstablished = v; }

    public boolean isRelayMode() { return relayMode; }
    public void setRelayMode(boolean v) { this.relayMode = v; }

    public int getRelayEpoch() { return relayEpoch; }
    public void setRelayEpoch(int relayEpoch) { this.relayEpoch = relayEpoch; }

    public boolean isHostRelayReady() { return hostRelayReady; }
    public void setHostRelayReady(boolean hostRelayReady) { this.hostRelayReady = hostRelayReady; }

    public boolean isGuestRelayReady() { return guestRelayReady; }
    public void setGuestRelayReady(boolean guestRelayReady) { this.guestRelayReady = guestRelayReady; }

    public boolean isRelayDataOpen() { return relayDataOpen; }
    public void setRelayDataOpen(boolean relayDataOpen) { this.relayDataOpen = relayDataOpen; }
}
