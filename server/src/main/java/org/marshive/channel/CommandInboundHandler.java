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
        
        // 3. 根据请求类型包装请求体
        final RequestBody<?> req;
        switch (type) {
            case CREATE: {
                // 3.1 检查是否有足够的字节读取房间名称长度
                int nameLength = in.getByte(1);
                if (!hasEnoughBytes(in, 2 + nameLength)) {
                    return; // 等待更多数据
                }
                
                // 3.2 去除头部
                in.skipBytes(2);
                
                // 3.2 读取房间名称
                byte[] nameBytes = new byte[nameLength];
                in.readBytes(nameBytes);
                String roomName = new String(nameBytes, StandardCharsets.UTF_8);
                // 3.2 构造请求体对象并向下传递
                req = new RequestBody<>(type, roomName);
                break;
            }
            case QUERY: {
                // 查询房间列表无请求体
                in.skipBytes(1);
                req = new RequestBody<>(type, null);
                break;
            }
            case JOIN: {
                if (!hasEnoughBytes(in, 5)) {
                    return; // 等待更多数据
                }
                // 3.1 读取房间ID
                in.skipBytes(1);
                int roomId = in.readInt();
                // 3.2 构造请求体对象并向下传递
                req = new RequestBody<>(type, roomId);
                break;
            }
            case START:
            case EXIT_ROOM:
            case LEAVE_ROOM: {
                // 这些请求无请求体
                in.skipBytes(1);
                req = new RequestBody<>(type, null);
                break;
            }
            default:
                throw new IOException("未知的请求: " + typeByte);
        }
        
        // 4. 将解析后的请求体对象传递给下一个处理器
        out.add(req);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("[{}]连接异常: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }
    
    private boolean hasEnoughBytes(ByteBuf in, int requiredLength) {
        return in.readableBytes() >= requiredLength;
    }
}
