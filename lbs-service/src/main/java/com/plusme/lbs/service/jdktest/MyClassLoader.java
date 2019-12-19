package com.plusme.lbs.service.jdktest;

/**
 * @author plusme
 * @create 2019-12-14 19:27
 */
public class MyClassLoader extends ClassLoader {

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return super.loadClass(name,true);
    }

}
