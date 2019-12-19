package com.plusme.lbs.service.jdktest;

import java.io.Serializable;
import java.lang.reflect.*;

/**
 * @author plusme
 * @create 2019-12-15 1:47
 */
public class MyPlusProxy<T> implements InvocationHandler, Serializable {

    Class<T> clazz;

    public Proxy parent;

    public <T> T newInstance() {
        Proxy proxy = (Proxy) MyProxy.newProxyInstance(PlusProxy.class.getClassLoader(), new Class[]{clazz}, this);
        parent = proxy;
        return (T) proxy;
    }

    MyPlusProxy(Class clazz) {
        this.clazz = clazz;
    }


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
        Field field = proxy.getClass().getSuperclass().getDeclaredField("h");
        field.setAccessible(true);

        return method.invoke(proxy, args);
    }

    /**
     * Backport of java.lang.reflect.Method#isDefault()
     */
    private boolean isDefaultMethod(Method method) {
        return (method.getModifiers()
                & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
                && method.getDeclaringClass().isInterface();
    }


}
