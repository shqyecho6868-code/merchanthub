package com.merchanthub.realtime.push;

import com.merchanthub.realtime.netty.ChannelRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** 把消息写进本节点上接收方的所有连接；写成功一条就标记已送达 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDelivery {

    private final ChannelRegistry channels;
    private final OfflineMessageStore store;

    /** @return 本节点上有没有这个接收方的活跃连接 */
    public boolean deliver(PushMessage msg) {
        List<Channel> targets = channels.channelsOf(msg.receiverKey());
        if (targets.isEmpty()) {
            // 路由说在本节点，实际已断开（断开和路由注销之间的空档）：消息已在表里是"待送达"，上线后补发
            log.info("接收方已不在本节点，留待上线补发 receiverKey={} msgId={}", msg.receiverKey(), msg.msgId());
            return false;
        }
        String json = store.toJson(msg);
        for (Channel ch : targets) {
            ch.writeAndFlush(new TextWebSocketFrame(json)).addListener(f -> {
                if (f.isSuccess()) {
                    store.markDelivered(msg.receiverKey(), msg.msgId());
                }
            });
        }
        log.info("【推送】已写入 {} 条连接 receiverKey={} type={} msgId={}", targets.size(), msg.receiverKey(), msg.type(), msg.msgId());
        return true;
    }
}
