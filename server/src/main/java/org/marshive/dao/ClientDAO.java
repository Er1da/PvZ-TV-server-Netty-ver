package org.marshive.dao;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.marshive.domain.Client;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientDAO {
    private static final ClientDAO INSTANCE = new ClientDAO();
    private ClientDAO() {}
    public static ClientDAO getInstance() { return INSTANCE; }
    
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
    
    public Collection<Client> allClients() {
        return clients.values();
    }
}
