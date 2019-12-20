package com.plusme.lbs.service.netty.utils;

import com.plusme.lbs.service.netty.utils.impl.DefaultTaskPromise;

/**
 * @author plusme
 * @create 2019-12-19 10:45
 */
public class UtilsTest {
    public static void main(String[] args) throws InterruptedException {
        final TaskPromise promise = new DefaultTaskPromise();

        final TaskFuture future = promise.getFuture();
        future.onFailure(new TaskCallback() {
            @Override
            public TaskFuture apply(TaskFuture f) {

                return f;
            }
        });

        future.onSuccess(new TaskCallback() {
            @Override
            public TaskFuture apply(TaskFuture f) {
                System.err.println(f.getAttr("msg"));
                return f;
            }

        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                promise.getFuture().addAttr("msg","success");
                promise.setSuccess(null);
            }
        }).start();


        Thread.sleep(30000000);
    }
}
