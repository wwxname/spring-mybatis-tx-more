package io.netty.channel.nio;


import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.EventLoopTaskQueueFactory;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import org.springframework.util.Assert;

import javax.validation.constraints.NotNull;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author plusme
 * @create 2019-12-20 10:39
 */
public class NioEventTest {
    static {

    }
    private static LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(1024);

    private static ThreadFactory factory = new ThreadFactory() {
        private volatile AtomicInteger num = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Assert.notNull(r, "r must not null");
            Thread thread = new Thread(r);
            thread.setName("Task-job-" + num.incrementAndGet());
            return thread;
        }
    };
    public static void main(String[] args) throws Exception {
        ExecutorService pool = new ThreadPoolExecutor(
                4, 1028, 10, TimeUnit.MINUTES, queue, factory);
        NioEventLoop loop = new NioEventLoop(
                null, new Executor() {
            @Override
            public void execute(Runnable command) {
                Thread thread = new Thread(command);
                thread.setName("executor-01");
                thread.start();
            }
        }, SelectorProvider.provider(), DefaultSelectStrategyFactory.INSTANCE.newSelectStrategy(),
                RejectedExecutionHandlers.reject(),
                new EventLoopTaskQueueFactory() {
                    @Override
                    public Queue<Runnable> newTaskQueue(int maxCapacity) {
                        return new LinkedBlockingQueue<Runnable>(20000000);
                    }
                }
        );
        DefaultChannelPromise promise = loop.bind(80);
        promise.sync();



    }
}
