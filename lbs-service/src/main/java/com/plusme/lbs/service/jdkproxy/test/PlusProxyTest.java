package com.plusme.lbs.service.jdkproxy.test;

import com.plusme.lbs.service.mapper.HelloMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author plusme
 * @create 2019-12-14 12:19
 */
public class PlusProxyTest {
    interface InterA{
        default void He(){}
    }
    abstract static class AbsA implements InterA{

        protected  Object invokeDefault(Method method,Object[] args) throws InvocationTargetException, IllegalAccessException {
            //Proxy.newProxyInstance()
            return method.invoke(this,args);
        }

    }

    static class A extends AbsA {
        @Override
        protected void finalize() throws Throwable {
            Method method = null;

            super.finalize();
        }

        public void fina() throws Throwable {
            finalize();
        }
    }


    public static void main(String[] args) throws Throwable {
        A a = new A();
        a.fina();
        System.err.println(a);

        //Proxy.newProxyInstance()
        System.getProperties().put("sun.misc.ProxyGenerator.saveGeneratedFiles","true");
        MyPlusProxy<HelloMapper> plusProxy = new MyPlusProxy<>(HelloMapper.class);
        HelloMapper mapper = plusProxy.newInstance();
        mapper.hello1();
    }
}
