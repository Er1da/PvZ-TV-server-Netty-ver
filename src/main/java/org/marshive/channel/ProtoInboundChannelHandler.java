package org.marshive.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

/**
 * 用于实现自定义协议的编解码器
 */
public class ProtoInboundChannelHandler extends ByteToMessageCodec<ByteBuf> {
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        // TODO
        out.writeBytes(msg);
    }
    
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // TODO
        out.add(in.readBytes(in.readableBytes()));
    }
}
