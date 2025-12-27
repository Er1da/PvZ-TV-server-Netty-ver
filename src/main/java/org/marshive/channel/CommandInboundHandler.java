package org.marshive.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.marshive.constant.RequestType;
import org.marshive.domain.data.RequestBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 处理入站命令的通道处理器。只解析命令并转化为 pojo ，不处理具体逻辑。
 */
@Slf4j
public class CommandInboundHandler extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // 1. 读取请求类型
        byte typeByte = in.getByte(0);
        RequestType type = RequestType.fromByte(typeByte);
        if (type == null) {
            throw new IllegalArgumentException("Unknown request type: " + typeByte);
        }
        
        // 2. 根据请求类型包装请求体
        final RequestBody<?> req;
        switch (type) {
            case CREATE: {
                // 2.1 检查数据包长度
                byte b1 = in.getByte(1);
                byte b2 = in.getByte(2);
                byte b3 = in.getByte(3);
                byte b4 = in.getByte(4);
                int nameLength = ((b1 & 0xFF) << 24) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 8) | (b4 & 0xFF);
                if (!hasEnoughBytes(in, 1 + 4 + nameLength)) {
                    return; // 等待更多数据
                }
                // 2.2 读取房间名称
                in.readByte(); // 读掉 type 字节
                in.readInt(); // 读掉 length 字节
                byte[] nameBytes = new byte[nameLength];
                in.readBytes(nameBytes);
                String roomName = new String(nameBytes, StandardCharsets.UTF_8);
                // 2.3 构造请求体对象并向下传递
                req = new RequestBody<>(type, roomName);
                break;
            }
            case QUERY: {
                // 查询房间列表无请求体
                in.readByte();
                req = new RequestBody<>(type, null);
                break;
            }
            case JOIN: {
                // 2.1 检查数据包长度
                if (!hasEnoughBytes(in, 1 + 4)) {
                    return; // 等待更多数据
                }
                // 2.2 读取房间ID
                in.readByte(); // 读掉 type 字节
                int roomId = in.readInt();
                // 2.3 构造请求体对象并向下传递
                req = new RequestBody<>(type, roomId);
                break;
            }
            case START:
            case EXIT_ROOM:
            case LEAVE_ROOM: {
                in.readByte();

                req = new RequestBody<>(type, null);
                break;
            }
            default:
                throw new IOException("未知的请求: " + typeByte);
        }
        
        // 3. 将解析后的请求体对象传递给下一个处理器
        out.add(req);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[{}]连接异常: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
    
    private static boolean hasEnoughBytes(ByteBuf in, int required) {
        return in.readableBytes() >= required;
    }
}
