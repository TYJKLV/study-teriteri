package com.teriteri.backend.im;

import com.teriteri.backend.im.handler.TokenValidationHandler;
import com.teriteri.backend.im.handler.WebSocketHandler;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class IMServer implements CommandLineRunner {

    // 存储每个用户的全部连接
    public static final Map<Integer, Set<Channel>> userChannel = new ConcurrentHashMap<>();

    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private RedisUtil redisUtil;

    @Override
    public void run(String... args) {
        // IMServer-Bootstrap 在执行完 start()后，线程结束。这里的 start()指的是 自定义的 start()
        new Thread(() -> {
            try {
                start();
                log.info("Netty WebSocket IM 服务器启动成功，监听端口 7071");
            } catch (Exception e) {
                log.error("Netty WebSocket IM 服务器启动失败", e);
            }
        }, "IMServer-Bootstrap").start();
    }

    public void start() throws InterruptedException {

        // 主从结构
        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup();

        // 绑定监听端口
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)

                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline(); // 处理流

                        // 添加 Http 编码解码器
                        pipeline.addLast(new HttpServerCodec())
                                // 添加处理大数据的组件
                                .addLast(new ChunkedWriteHandler())
                                // 对 Http 消息做聚合操作方便处理，产生 FullHttpRequest 和 FullHttpResponse
                                // 1024 * 64 是单条信息最长字节数，也就是 64KB
                                .addLast(new HttpObjectAggregator(1024 * 64))
                                .addLast(new TokenValidationHandler(jwtUtil,redisUtil))
                                // 添加 WebSocket 支持
                                .addLast(new WebSocketServerProtocolHandler("/im"))
                                .addLast(new WebSocketHandler(redisUtil));

                    }
                });

        // .sync() 在等什么？
        // bootstrap.bind(7071)：发起绑定操作，返回一个 ChannelFuture。这个绑定是异步的，Netty 会在自己的线程里去做。
        // .sync()：在当前线程阻塞，直到绑定操作完成（成功或失败）
        ChannelFuture future = bootstrap.bind(7071).sync();

    }
}
// TODO
/*
    1. 除了这种 .bind().sync() 绑定方式以外，是否还有其它绑定方式；还是说 这个语法就是固定的
    2. userChannel这个map集合，说是存储用户的全部连接，但它是否依靠 数据库表呢，在我的理解里面
       因为 怎么说呢，在我的理解里面，用户的全部连接，指的就是 用户的全部好友，但在真实的视频软件中，肯定不能存储所有连接
       而是存储几个固定的 + 有消息的进行推送
       应该还是要依赖数据库的
 */