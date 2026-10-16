package com.teriteri.backend.im.handler;

import com.alibaba.fastjson2.JSON;
import com.teriteri.backend.im.IMServer;
import com.teriteri.backend.pojo.Command;
import com.teriteri.backend.pojo.CommandType;
import com.teriteri.backend.pojo.IMResponse;
import com.teriteri.backend.utils.RedisUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// TODO
// 用到了 fastjson，待替换，看看是否可以解决
@Slf4j
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    // 不能使用 @Autowired，因为 这个类 没有相关注解，不会被 Spring 扫描并管理
    private final RedisUtil redisUtil;
    private final ChatHandler chatHandler;

    public WebSocketHandler(RedisUtil redisUtil, ChatHandler chatHandler) {
        this.redisUtil = redisUtil;
        this.chatHandler = chatHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame tx) {

        try {
            Command command = JSON.parseObject(tx.text(), Command.class);
            // 根据code分发不同处理程序
            switch (CommandType.match(command.getCode())) {
                case CONNECTION: // 如果是连接消息就不需要做任何操作了，因为连接上的话在token鉴权那就做了
                    break;
                case CHAT_SEND:
                    chatHandler.send(ctx, tx);
                    break;
                case CHAT_WITHDRAW:
                    chatHandler.withdraw(ctx, tx);
                    break;
                default:
                    ctx.channel().writeAndFlush(IMResponse.error("不支持的CODE " + command.getCode()));
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 消息失败", e);
            ctx.channel().writeAndFlush(IMResponse.error("服务器处理消息失败"));
        }
    }

    /**
     * 连接断开时执行 将channel从集合中移除 如果集合为空则从Map中移除该用户 即离线状态
     *
     * @param ctx
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Integer uid = (Integer) ctx.channel().attr(AttributeKey.valueOf("userId")).get();

        // channelInactive() 触发情况之一：ctx.close()，若 TokenValidationHandler 中 校验失败，走 else 逻辑，执行 ctx.close()
        // 因此，存在 uid 为 null，添加判断
        if (uid != null) {
            Set<Channel> userChannels = IMServer.userChannel.get(uid);
            if (userChannels != null) {
                userChannels.remove(ctx.channel());
                if (userChannels.isEmpty()) {
                    IMServer.userChannel.remove(uid);
                    redisUtil.deleteKeysWithPrefix("whisper:" + uid + ":");
                    redisUtil.delMember("login_member", uid);
                }
            }
        }
        ctx.fireChannelInactive();
    }
}
