package com.plusme.lbs.service;

import io.netty.channel.nio.NioEventLoop;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cglib.core.DebuggingClassWriter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.util.Assert;

import javax.validation.constraints.NotNull;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
@ComponentScan("com.plusme")
@EnableJpaRepositories(value = "com.plusme.lbs.service.repository")
@MapperScan("com.wayz.lbs.service.mapper")
public class ServiceApp {
    NioEventLoop nioEventLoop;

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

    public static void main(String[] args) {
        System.getProperties().put("sun.misc.ProxyGenerator.saveGeneratedFiles","true");
        System.setProperty(DebuggingClassWriter.DEBUG_LOCATION_PROPERTY, "com");
        ExecutorService fixedThreadPool = new ThreadPoolExecutor(
                4, 1028, 10, TimeUnit.MINUTES, queue, factory);

        fixedThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                System.err.println(Thread.currentThread().getName());
            }
        });
        fixedThreadPool.submit(new Runnable() {
            @Override
            public void run() {
                System.err.println(Thread.currentThread().getName());
            }
        });
        SpringApplication.run(ServiceApp.class,args);

    }
}