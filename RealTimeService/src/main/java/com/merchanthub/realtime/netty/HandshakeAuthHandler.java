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
 * Authentication before the WebSocket handshake: the token comes from the Authorization header (optional Bearer prefix) or the ?token= query param (the browser WebSocket API can't set custom headers).
 * <ol>
 *   <li>JWT signature and expiry check (same secret as the gateway)</li>
 *   <li>Compare with the token user-tenant stored in Redis at login: after re-login / logout the old token is invalid immediately (same approach as the original IM)</li>
 *   <li>Receiver is derived from identity: merchant staff -> merchant:{tenantId} (staff of one shop share merchant notices); buyer -> buyer:{userId}</li>
 * </ol>
 * On success the receiver is attached to the channel and the WebSocket protocol handler completes the handshake; on failure respond 401 and close.
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
        // The protocol handler matches the path exactly, so strip the query params (they contain the token, which shouldn't travel further anyway)
        req.setUri(uri.path());
        ctx.pipeline().remove(this);   // authenticate only once
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
            log.info("WebSocket handshake rejected: token invalidated (re-login / logout) userId={}", userId);
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
        return null;   // platform admins and the like get no order pushes
    }
}
