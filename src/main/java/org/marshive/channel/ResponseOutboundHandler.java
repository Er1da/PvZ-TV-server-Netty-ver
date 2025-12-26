package org.marshive.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;
import org.marshive.domain.Room;
import org.marshive.domain.data.ResponseBody;

import java.util.Collection;

/**
 * 处理出站响应的通道处理器。只将 pojo 转化为命令并编码发送，不处理具体逻辑。
 */
@Slf4j
public class ResponseOutboundHandler extends MessageToByteEncoder<ResponseBody<?>> {
    @Override
    protected void encode(ChannelHandlerContext ctx, ResponseBody<?> body, ByteBuf out) throws Exception {
        // 1. 写入响应类型
        out.writeByte(body.getType().code);
        
        // 2. 检查是否有负载
        if (body.getData() == null) return;
        
        // 3. 根据不同的响应类型写入负载
        switch (body.getType()) {
            case ROOM_CREATED:
            case GUEST_LEFT:
            case GUEST_JOINED: {
                final int roomId = (Integer) body.getData();
                out.writeByte(4);
                out.writeInt(roomId);
                break;
            }
            case ROOM_LIST: {
                // TODO: 名字过长可能导致缓冲区溢出，需要限制名字长度
                // payload: [count:1] + count*([roomId:4][flags:1][nameLen:1][nameBytes])
                final Collection<Room> rooms = (Collection<Room>) body.getData();
                final ByteBuf temp = ctx.alloc().buffer();
                
                // 1. 写入总数
                final int size = Math.min(rooms.size(), 255);
                temp.writeByte(size);
                
                // 2. 遍历房间列表
                rooms.stream().limit(size).forEach(r -> {
                    // 2.1 写入房间 ID
                    temp.writeInt(r.getId());
                    
                    // 2.2 写入状态标志
                    int flags = 0;
                    if (r.isFull()) flags |= 1;
                    if (r.isGaming()) flags |= 2;
                    temp.writeByte(flags);
                    
                    // 2.3 写入房间名称
                    byte[] nameBytes = r.getName().getBytes();
                    int nameLen = Math.min(nameBytes.length, 255);
                    temp.writeByte(nameLen);
                    temp.writeBytes(nameBytes, 0, nameLen);
                });
                
                // 3. 将临时缓冲区的数据写入输出缓冲区
                out.writeByte(temp.readableBytes());
                out.writeBytes(temp);
                
                // 4. 释放临时缓冲区
                temp.release();
                break;
            }
        }
    }
}
