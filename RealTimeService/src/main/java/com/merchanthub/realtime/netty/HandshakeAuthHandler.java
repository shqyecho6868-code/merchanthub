package com.merchanthub.realtime.netty;

import com.merchanthub.common.constant.CommonConstant;
import com.merchanthub.common.security.JwtCodec;
import com.merchanthub.common.tenant.Role;
import com.merchanthub.realtime.push.PushMessage;
import io.jsonwebtoken.Claims;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * WebSocket 握手前鉴权：token 来自请求头 Authorization（可带 Bearer 前缀），或查询参数 ?token=（浏览器的 WebSocket API 不能自定义请求头）。
 * <ol>
 *   <li>JWT 签名、过期校验（和网关同一个密钥）</li>
 *   <li>和 Redis 里 user-tenant 登录时存的 token 比对：重新登录 / 登出后旧 token 立刻失效（沿用原 IM 的做法）</li>
 *   <li>按身份决定接收方：商家员工 → merchant:{tenantId}（同店员工共享商家通知）；买家 → buyer:{userId}</li>
 * </ol>
 * 通过后把接收方挂在连接上，交给 WebSocket 协议处理器完成握手；不通过回 401 并关闭。
 */
@Slf4j
@RequiredArgsConstructor
public class HandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    private final JwtCodec jwt;
    private final StringRedisTemplate redis;
    private final String wsPath;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest req)) {
            ctx.fireChannelRead(msg);
            return;
        }
        QueryStringDecoder uri = new QueryStringDecoder(req.uri());
        String receiver = authenticate(req, uri);
        if (receiver == null) {
            ReferenceCountUtil.release(req);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED,
                    Unpooled.copiedBuffer("unauthorized", StandardCharsets.UTF_8));
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ctx.channel().attr(ChannelRegistry.RECEIVER).set(receiver);
        // 协议处理器按路径精确匹配，去掉查询参数（里面有 token，也不该继续往后传）
        req.setUri(uri.path());
        ctx.pipeline().remove(this);   // 鉴权只做一次
        ctx.fireChannelRead(req);
    }

    private String authenticate(FullHttpRequest req, QueryStringDecoder uri) {
        if (!wsPath.equals(uri.path())) {
            return null;
        }
        String token = req.headers().get("Authorization");
        if (token == null || token.isBlank()) {
            List<String> q = uri.parameters().get("token");
            token = q == null || q.isEmpty() ? null : q.get(0);
        }
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        Claims claims = jwt.parse(token);
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        String userId = claims.getSubject();
        String stored = redis.opsForValue().get(CommonConstant.ACCESS_TOKEN_PREFIX + userId);
        if (!token.equals(stored)) {
            log.info("WebSocket 握手拒绝：token 已失效（重新登录 / 登出）userId={}", userId);
            return null;
        }
        Role role = Role.parse(claims.get(JwtCodec.CLAIM_ROLE, String.class));
        if (role != null && role.isMerchant()) {
            String tenantId = claims.get(JwtCodec.CLAIM_TENANT_ID, String.class);
            return tenantId == null ? null : PushMessage.merchant(Long.parseLong(tenantId));
        }
        if (role == Role.BUYER) {
            return PushMessage.buyer(Long.parseLong(userId));
        }
        return null;   // 平台管理员等没有订单推送
    }
}
