package com.plusme.lbs.service.jdktest;

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * @author plusme
 * @create 2019-12-14 11:54
 */
public class PlusProxy<T> implements InvocationHandler, Serializable {

    Class<T> clazz;

    public <T> T newInstance() {
        return (T) Proxy.newProxyInstance(PlusProxy.class.getClassLoader(), new Class[]{clazz}, this);
    }

    PlusProxy(Class clazz) {
        this.clazz = clazz;
    }


    private final MethodHandleLookup methodHandleLookup = MethodHandleLookup.getMethodHandleLookup();

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            } else if (isDefaultMethod(method)) {
                return invokeDefaultMethod(proxy, method, args);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.err.println("普通的");
        return null;
    }


    private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
            throws Throwable {
        return getMethodHandle(method).bindTo(proxy).invokeWithArguments(args);
    }

    /**
     * Backport of java.lang.reflect.Method#isDefault()
     */
    private boolean isDefaultMethod(Method method) {
        return (method.getModifiers()
                & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
                && method.getDeclaringClass().isInterface();
    }

    private MethodHandle getMethodHandle(Method method) throws Exception {
        System.err.println(methodHandleLookup.lookup(method).hashCode());
        System.err.println(methodHandleLookup.lookup(method).hashCode());
        return methodHandleLookup.lookup(method);
    }

}
