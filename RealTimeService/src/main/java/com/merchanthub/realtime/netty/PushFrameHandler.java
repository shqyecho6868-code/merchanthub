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
 * 握手完成后的连接生命周期：
 * <ul>
 *   <li>握手完成 → 记进本节点连接表 → 注册路由 → 补发离线消息（先注册路由再补发：补发期间新来的消息也能直接推到）</li>
 *   <li>收到 "ping" → 回 "pong" + 路由续期（客户端每 30 秒一次，路由 TTL 90 秒，丢两次心跳也不会误判下线）</li>
 *   <li>读空闲超时 → 断开（客户端没了却没发 FIN，比如断网）</li>
 *   <li>连接断开 → 本节点上这个接收方没有连接了 → 注销自己的路由</li>
 * </ul>
 * 这个处理器挂在独立的业务线程组上（见 NettyServer）：这里会访问 Redis / MySQL，不能阻塞 Netty 的 IO 线程。
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
            log.info("读空闲超时，断开 receiverKey={}", ctx.channel().attr(ChannelRegistry.RECEIVER).get());
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
        // 推送是单向的：客户端发来的其他内容一律忽略
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String lastOnThisNode = channels.remove(ctx.channel());
        if (lastOnThisNode != null) {
            try {
                routes.unregister(lastOnThisNode);
            } catch (RuntimeException e) {
                // Redis 不可用：不要紧，路由 TTL 到了会自己消失
                log.warn("注销路由失败，等 TTL 过期 receiverKey={}", lastOnThisNode, e);
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("连接异常，关闭 receiverKey={}", ctx.channel().attr(ChannelRegistry.RECEIVER).get(), cause);
        ctx.close();
    }
}
