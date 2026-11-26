package com.merchanthub.realtime.netty;

import com.merchanthub.realtime.push.OfflineMessageStore;
import com.merchanthub.realtime.route.RouteRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Connection lifecycle after the handshake:
 * <ul>
 *   <li>Handshake complete -> add to this node's connection table -> register route -> redeliver offline messages (route first, then redelivery, so messages arriving during redelivery are pushed directly too)</li>
 *   <li>Receive "ping" -> reply "pong" + renew route (client pings every 30 seconds, route TTL is 90 seconds, so missing two heartbeats won't wrongly mark it offline)</li>
 *   <li>Read idle timeout -> disconnect (the client is gone without sending FIN, e.g. network loss)</li>
 *   <li>Connection closed -> this receiver has no connections left on this node -> unregister our own route</li>
 * </ul>
 * This handler runs on a separate business thread group (see NettyServer): it accesses Redis / MySQL and must not block Netty's IO threads.
 */
@Slf4j
@RequiredArgsConstructor
public class PushFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    public static final String PING = "ping";
    public static final String PONG = "pong";

    private final ChannelRegistry channels;
    private final RouteRegistry routes;
    private final OfflineMessageStore store;

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String key = ctx.channel().attr(ChannelRegistry.RECEIVER).get();
            channels.add(key, ctx.channel());
            routes.register(key);
            store.replay(key, ctx.channel());
            return;
        }
        if (evt instanceof IdleStateEvent) {
            log.info("Read idle timeout, disconnecting receiverKey={}", ctx.channel().attr(ChannelRegistry.RECEIVER).get());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        if (PING.equals(frame.text())) {
            String key = ctx.channel().attr(ChannelRegistry.RECEIVER).get();
            if (key != null) {
                routes.renew(key);
            }
            ctx.writeAndFlush(new TextWebSocketFrame(PONG));
        }
        // Push is one-way: anything else sent by the client is ignored
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String lastOnThisNode = channels.remove(ctx.channel());
        if (lastOnThisNode != null) {
            try {
                routes.unregister(lastOnThisNode);
            } catch (RuntimeException e) {
                // Redis unavailable: no big deal, the route disappears on its own when the TTL expires
                log.warn("Failed to unregister route, waiting for TTL expiry receiverKey={}", lastOnThisNode, e);
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Connection error, closing receiverKey={}", ctx.channel().attr(ChannelRegistry.RECEIVER).get(), cause);
        ctx.close();
    }
}
