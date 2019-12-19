package com.plusme.lbs.service.mapper;

/**
 * @author plusme
 * @create 2019-12-14 12:20
 */

public interface HelloMapper {
    default void hello1() {
        System.err.println("hello1");
        hello2();
    }

    void hello2();
}
