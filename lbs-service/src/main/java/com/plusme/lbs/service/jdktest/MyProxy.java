package com.plusme.lbs.service.jdktest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * @author plusme
 * @create 2019-12-15 1:46
 */
public class MyProxy extends Proxy {
    /**
     * Constructs a new {@code Proxy} instance from a subclass
     * (typically, a dynamic proxy class) with the specified value
     * for its invocation handler.
     *
     * @param h the invocation handler for this proxy instance
     * @throws NullPointerException if the given invocation handler, {@code h},
     *                              is {@code null}.
     */
    protected MyProxy(InvocationHandler h) {
        super(h);
        ((MyPlusProxy)h).parent = this;
    }

}
