package com.plusme.lbs.service.netty;

import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.ThreadFactory;

/**
 * @author plusme
 * @create 2019-12-17 15:02
 */
public class MyTest {
    public static void main(String[] args) {
        new MyNioEventLoop(new NioEventLoopGroup(1), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread();
            }
        }, false).run();
    }
}