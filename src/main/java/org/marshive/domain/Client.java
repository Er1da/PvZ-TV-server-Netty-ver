package org.marshive.domain;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.marshive.domain.data.ResponseBody;
import org.marshive.util.IOUtils;
import org.marshive.util.RoomManager;

/**
 * 将连接逻辑交给{@link org.marshive.channel.RequestHandler}预处理，
 * 将业务逻辑交给此类处理
 */
@Slf4j
@Data
public class Client {
    private Channel channel;
    
    private Room currentRoom;
    private boolean isHost = false;
    
    public Client(Channel channel) {
        this.channel = channel;
    }
    
    public void back(ResponseBody<?> body) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(body);
        }
    }
    
    public void clearState() {
        this.currentRoom = null;
        this.isHost = false;
    }
    
    public void forwardLoop(Client opponent) {
        // 1. 校验双方连接状态
        if (opponent == null) return;
        final Channel a = this.channel;
        final Channel b = opponent.channel;
        if (a == null || b == null) return;
        if (a == b) return;
        if (!a.isActive() || !b.isActive()) return;
        
        // 2. 进行数据转发
        Future<?> relayFuture = IOUtils.relay(a, b);
        
        // 3. 添加结束监听器
        relayFuture.addListener(future -> {
            log.info("Relay between clients {} and {} ended.", a.remoteAddress(), b.remoteAddress());
            if (isHost && currentRoom != null) {
                RoomManager.getInstance().removeRoom(currentRoom.getId());
            }
        });
    }
    
    public void close() {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }
}
