package org.marshive.old;

public class Room {
    private final int id;
    private final String name;
    private final ClientHandler host;
    private volatile ClientHandler guest;
    private volatile boolean gaming = false;

    public Room(int id, String name, ClientHandler host) {
        this.id = id;
        this.name = name;
        this.host = host;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public ClientHandler getHost() { return host; }
    public ClientHandler getGuest() { return guest; }
    public void setGuest(ClientHandler g) { this.guest = g; }

    public boolean isGaming() { return gaming; }
    public void setGaming(boolean v) { this.gaming = v; }

    public boolean isFull() { return host != null && guest != null; }
}
