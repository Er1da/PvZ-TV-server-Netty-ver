package org.marshive.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.marshive.constant.ResponseType;
import org.marshive.domain.Client;
import org.marshive.domain.Room;
import org.marshive.domain.data.RequestBody;
import org.marshive.domain.data.ResponseBody;
import org.marshive.util.ClientManager;
import org.marshive.util.RoomManager;

import java.util.Collection;

/**
 * 处理请求的通道处理器。负责具体的业务逻辑处理。
 * 类似于 Service 层。
 */
@Slf4j
public class RequestHandler extends ChannelInboundHandlerAdapter {
    private static final RoomManager roomManager = RoomManager.getInstance();
    private static final ClientManager clientManager = ClientManager.getInstance();
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final RequestBody<?> body = (RequestBody<?>) msg;
        final Client client = clientManager.getClient(ctx.channel());
        switch (body.getType()) {
            case CREATE: {
                final String roomName = (String) body.getPayload();
                Room room = roomManager.createRoom(roomName, client);
                client.back(ResponseBody.of(ResponseType.ROOM_CREATED, room.getId()));
                break;
            }
            case QUERY: {
                final Collection<Room> rooms = roomManager.allRooms();
                client.back(ResponseBody.of(ResponseType.ROOM_LIST, rooms));
                break;
            }
            case JOIN: {
                final Integer roomId = (Integer) body.getPayload();
                final boolean success = roomManager.joinRoom(roomId, client);
                if (success) {
                    client.back(ResponseBody.of(ResponseType.JOIN_RESULT, roomId));
                    // 通知房主有新房客加入
                    Room room = roomManager.getRoom(roomId);
                    Client host = room.getHost();
                    if (host != null) {
                        host.back(ResponseBody.of(ResponseType.GUEST_JOINED, roomId));
                    }
                } else {
                    // 0作为加入失败的标志
                    client.back(ResponseBody.of(ResponseType.JOIN_RESULT, 0));
                }
                break;
            }
            case START: {
                // 1. 检查操作合法性
                final boolean isHost = client.isHost();
                final Room currentRoom = client.getCurrentRoom();
                if (!isHost || currentRoom == null) {
                    client.back(ResponseBody.NotHost);
                    break;
                } else if (!currentRoom.isFull()) {
                    client.back(ResponseBody.NotReady);
                    break;
                }
                
                // 2. 设置房间状态
                currentRoom.setGaming(true);
                
                // 3. 通知双方游戏开始
                final Client guest = currentRoom.getGuest();
                client.back(ResponseBody.of(ResponseType.RELAY_BEGIN));
                guest.back(ResponseBody.of(ResponseType.RELAY_BEGIN));
                break;
            }
            case EXIT_ROOM: {
                // 1. 检查操作合法性
                final Room currentRoom = client.getCurrentRoom();
                if (!client.isHost() || currentRoom == null) {
                    client.back(ResponseBody.NotHost);
                    break;
                } else if (currentRoom.isGaming()) {
                    client.back(ResponseBody.NotReady);
                    break;
                }
                
                // 2. 通知房客房主已退出
                final Client guest = currentRoom.getGuest();
                if (guest != null) {
                    guest.back(ResponseBody.of(ResponseType.ROOM_EXITED, null));
                    guest.setCurrentRoom(null);
                    guest.setHost(false);
                }
                
                // 3. 清理房间
                final int roomId = currentRoom.getId();
                roomManager.removeRoom(roomId);
                
                // 4. 清理房主状态
                client.clearState();
                
                // 5. 返回数据
                client.back(ResponseBody.of(ResponseType.ROOM_EXITED));
                break;
            }
            case LEAVE_ROOM: {
                // 1. 检查操作合法性
                final Room currentRoom = client.getCurrentRoom();
                if (currentRoom == null) {
                    client.back(ResponseBody.BadRequest);
                    break;
                } else if (currentRoom.isGaming()) {
                    client.back(ResponseBody.NotReady);
                    break;
                } else if (roomManager.getRoom(currentRoom.getId()) == null) {
                    client.back(ResponseBody.NotFound);
                }
                
                // 2. 尝试离开房间
                final int roomId = currentRoom.getId();
                final boolean success = roomManager.leaveAsGuest(roomId, client);
                if (!success) {
                    client.back(ResponseBody.BadRequest);
                    break;
                }
                
                // 3. 通知房主房客已离开
                final Client host = currentRoom.getHost();
                if (host != null) {
                    host.back(ResponseBody.of(ResponseType.GUEST_LEFT, roomId));
                }
                
                // 4. 清理房客状态
                client.clearState();
                
                // 5. 返回数据
                client.back(ResponseBody.of(ResponseType.ROOM_EXITED));
                break;
            }
            default: {
                log.warn("[{}]收到未知请求类型: {}", ctx.channel().remoteAddress(), body.getType());
                break;
            }
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[{}]连接异常: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ChannelFuture close = ctx.close();
        close.addListener(future -> log.info("[{}]连接已关闭", ctx.channel().remoteAddress()));
    }
}
