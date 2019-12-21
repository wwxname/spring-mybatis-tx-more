package com.plusme.lbs.service.zk;

import java.io.IOException;

/**
 * @author plusme
 * @create 2019-12-21 4:05
 */
public class Test {
    public static void main(String[] args) throws IOException {
        ServerCnxnFactory factory = ServerCnxnFactory.createFactory(8080,10);
        factory.start();
    }
}
