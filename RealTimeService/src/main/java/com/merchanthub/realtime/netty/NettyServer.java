package com.merchanthub.realtime.netty;

import com.merchanthub.common.security.JwtCodec;
import com.merchanthub.realtime.config.NodeId;
import com.merchanthub.realtime.config.RealtimeProperties;
import com.merchanthub.realtime.push.OfflineMessageStore;
import com.merchanthub.realtime.route.RouteRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Netty WebSocket server (inherited from the original IM; E1 changes: configurable port, receiver derived from identity during auth, business handling moved to a separate thread group) */
@Slf4j
@Component
@RequiredArgsConstructor
public class NettyServer {

    public static final String WS_PATH = "/ws";

    private final RealtimeProperties props;
    private final JwtCodec jwt;
    private final StringRedisTemplate redis;
    private final ChannelRegistry channels;
    private final RouteRegistry routes;
    private final OfflineMessageStore store;
    private final NodeId nodeId;

    private NioEventLoopGroup boss;
    private NioEventLoopGroup workers;
    /** Business thread group: post-handshake handlers access Redis / MySQL, so they run here instead of occupying IO threads */
    private DefaultEventExecutorGroup business;
    private Channel serverChannel;

    @PostConstruct
    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        business = new DefaultEventExecutorGroup(4);
        long idle = props.getIdleTimeout().toSeconds();
        ServerBootstrap b = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new IdleStateHandler(idle, 0, 0, TimeUnit.SECONDS));
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(65536));
                        p.addLast(new HandshakeAuthHandler(jwt, redis, WS_PATH));
                        p.addLast(new WebSocketServerProtocolHandler(WS_PATH));
                        p.addLast(business, new PushFrameHandler(channels, routes, store));
                    }
                });
        serverChannel = b.bind(props.getNettyPort()).sync().channel();
        log.info("WebSocket server started ws://0.0.0.0:{}{} node={}", port(), WS_PATH, nodeId);
    }

    /** Actual listening port (random when configured as 0) */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        // Graceful shutdown makes every connection go through channelInactive -> unregister this node's routes; kill -9 never gets here, so we rely on route TTL expiry
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        business.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
