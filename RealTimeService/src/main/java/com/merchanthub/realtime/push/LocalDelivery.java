package com.merchanthub.realtime.push;

import com.merchanthub.realtime.netty.ChannelRegistry;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** Write the message to all of the receiver's connections on this node; mark delivered once any write succeeds */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDelivery {

    private final ChannelRegistry channels;
    private final OfflineMessageStore store;

    /** @return whether this node has an active connection for this receiver */
    public boolean deliver(PushMessage msg) {
        List<Channel> targets = channels.channelsOf(msg.receiverKey());
        if (targets.isEmpty()) {
            // Route says this node, but the connection has already closed (the gap between disconnect and route unregistration): the message is already "pending" in the table and will be redelivered on coming online
            log.info("Receiver no longer on this node, kept for redelivery on coming online receiverKey={} msgId={}", msg.receiverKey(), msg.msgId());
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
        log.info("[push] Written to {} connections receiverKey={} type={} msgId={}", targets.size(), msg.receiverKey(), msg.type(), msg.msgId());
        return true;
    }
}
