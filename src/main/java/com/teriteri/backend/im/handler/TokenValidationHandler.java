package com.teriteri.backend.im.handler;

import com.alibaba.fastjson2.JSON;
import com.teriteri.backend.im.IMServer;
import com.teriteri.backend.pojo.Command;
import com.teriteri.backend.pojo.IMResponse;
import com.teriteri.backend.pojo.User;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class TokenValidationHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    // final 修饰 字段，构造器进行初始化，避免被修改
    private final JwtUtil jwtUtil;
    private final RedisUtil redisUtil;

    public TokenValidationHandler(JwtUtil jwtUtil, RedisUtil redisUtil) {
        this.jwtUtil = jwtUtil;
        this.redisUtil = redisUtil;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame tx) {
        Command command = JSON.parseObject(tx.text(), Command.class);
        String token = command.getContent();

        Integer uid = isValidToken(token);
        if (uid != null) {
            // 将uid绑到ctx上
            ctx.channel().attr(AttributeKey.valueOf("userId")).set(uid); // attribute: 属性，特质

            // 这段 if/else 因为采用了 HashSet 进行 add()，存在并发风险；因此，使用 computeIfAbsent() 替代
            /*if (IMServer.userChannel.get(uid) == null) {
                Set<Channel> set = new HashSet<>();
                set.add(ctx.channel());
                IMServer.userChannel.put(uid, set);
            } else {
                IMServer.userChannel.get(uid).add(ctx.channel());
            }*/

            // 将channel存起来
            IMServer.userChannel
                    // 存在 uid为 key 的 set集合，则返回；若不存在，创建一个线程安全的 set集合，放入Map并返回
                .computeIfAbsent(uid, k -> ConcurrentHashMap.newKeySet())
                .add(ctx.channel());

            redisUtil.addMember("login_member", uid);   // 将用户添加到在线用户集合
            // 移除token验证处理器，以便以后使用无需判断
            ctx.pipeline().remove(TokenValidationHandler.class);
            // 保持消息的引用计数，以确保消息不会被释放
            tx.retain();
            // 将消息传递给下一个处理器
            ctx.fireChannelRead(tx);
        } else {
            ctx.channel().writeAndFlush(IMResponse.error("登录已过期"));
            ctx.close();
        }
    }

    /**
     * 进行JWT验证
     * @param token Bearer JWT
     * @return  返回用户ID 验证不通过则返回null
     */
    private Integer isValidToken(String token) {
        if (!StringUtils.hasText(token) || !token.startsWith("Bearer ")) {
            return null;
        }

        token = token.substring(7);

        // 解析token
        boolean verifyToken = jwtUtil.verifyToken(token);
        if (!verifyToken) {
            log.error("当前token已过期");
            return null;
        }
        String userId = JwtUtil.getSubjectFromToken(token);
        String channel = JwtUtil.getClaimFromToken(token, "channel");
        User user = redisUtil.getObject("security:" + channel + ":" + userId, User.class);

        if (user == null) {
            log.error("用户未登录");
            return null;
        }

//        log.info("成功通过验证！");
        return user.getUid();
    }

}
