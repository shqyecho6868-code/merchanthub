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

/** Netty WebSocket 服务（原 IM 已有，E1 调整：端口可配、鉴权改为按身份算接收方、业务处理挪到独立线程组） */
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
    /** 业务线程组：握手后的处理器要访问 Redis / MySQL，放这里跑，不占 IO 线程 */
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
        log.info("WebSocket 服务已启动 ws://0.0.0.0:{}{} node={}", port(), WS_PATH, nodeId);
    }

    /** 实际监听端口（配置成 0 时是随机端口） */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        // 优雅关闭会让每条连接走 channelInactive → 注销本节点路由；进程被 kill -9 时走不到这里，靠路由 TTL 过期
        workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        business.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
