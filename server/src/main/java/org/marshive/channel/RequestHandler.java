package org.marshive.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.marshive.constant.ResponseType;
import org.marshive.domain.Client;
import org.marshive.domain.Room;
import org.marshive.domain.data.JoinResult;
import org.marshive.domain.data.RequestBody;
import org.marshive.domain.data.ResponseBody;
import org.marshive.dao.ClientDAO;
import org.marshive.dao.RoomDAO;

import java.util.Collection;

/**
 * 处理请求的通道处理器。负责具体的业务逻辑处理。
 * 类似于 Service 层。
 */
@Slf4j
public class RequestHandler extends ChannelInboundHandlerAdapter {
    private static final RoomDAO ROOM_DAO = RoomDAO.getInstance();
    private static final ClientDAO CLIENT_DAO = ClientDAO.getInstance();
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        final RequestBody<?> body = (RequestBody<?>) msg;
        final Client client = CLIENT_DAO.getClient(ctx.channel());
        switch (body.getType()) {
            case CREATE: {
                final String roomName = (String) body.getPayload();
                Room room = ROOM_DAO.createRoom(roomName, client);
                client.back(ResponseBody.of(ResponseType.ROOM_CREATED, room.getId()));
                break;
            }
            case QUERY: {
                final Collection<Room> rooms = ROOM_DAO.allRooms();
                client.back(ResponseBody.of(ResponseType.ROOM_LIST, rooms));
                break;
            }
            case JOIN: {
                final Integer roomId = (Integer) body.getPayload();
                final Room room = ROOM_DAO.getRoom(roomId);
                // 1. 当房间不存在时
                if (room == null) {
                    client.back(ResponseBody.of(ResponseType.JOIN_RESULT, new JoinResult(false, 0)));
                    break;
                }
                
                // 2. 当房间已开始游戏时
                if (room.isGaming()) {
                    client.back(ResponseBody.NotReady);
                    break;
                }
                
                // 3. 当房间已满时
                if (room.isFull()) {
                    client.back(ResponseBody.RoomFull);
                    break;
                }
                
                final boolean success = ROOM_DAO.joinRoom(roomId, client);
                if (success) {
                    client.back(ResponseBody.of(ResponseType.JOIN_RESULT, new JoinResult(true, roomId)));
                    // 通知房主有新房客加入
                    Client host = room.getHost();
                    if (host != null) {
                        host.back(ResponseBody.of(ResponseType.GUEST_JOINED, roomId));
                    }
                } else {
                    // 0作为加入失败的标志
                    client.back(ResponseBody.of(ResponseType.JOIN_RESULT, new JoinResult(false, 0)));
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
                
                // 4. 启动数据转发
                client.forwardLoop(guest);
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
                ROOM_DAO.removeRoom(roomId);
                
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
                } else if (ROOM_DAO.getRoom(currentRoom.getId()) == null) {
                    client.back(ResponseBody.NotFound);
                }
                
                // 2. 尝试离开房间
                final int roomId = currentRoom.getId();
                final boolean success = ROOM_DAO.leaveAsGuest(roomId, client);
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
