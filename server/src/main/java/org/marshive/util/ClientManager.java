package org.marshive.util;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.marshive.domain.Client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientManager {
    private static final ClientManager INSTANCE = new ClientManager();
    private ClientManager() {}
    public static ClientManager getInstance() { return INSTANCE; }
    
    private final Map<ChannelId, Client> clients = new ConcurrentHashMap<>();
    
    public void createClient(Channel channel) {
        Client client = new Client(channel);
        clients.put(channel.id(), client);
        channel.closeFuture().addListener(future -> clients.remove(channel.id()));
    }
    
    public Client getClient(Channel channel) {
        return getClient(channel.id());
    }
    
    public Client getClient(ChannelId channelId) {
        return clients.get(channelId);
    }
    
    public void closeAllClients() {
        clients.values().forEach(Client::close);
    }
}
