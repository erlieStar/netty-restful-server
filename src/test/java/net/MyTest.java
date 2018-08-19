package net;

import org.junit.Test;

public class MyTest {

    @Test
    public void test1() {
        String uri = "http://localhost:8080/user/1/album/10?build=103&test=100";
//        String[] strs = uri.split("\\?");
//        for (String str : strs) {
//            System.out.println(str);
//        }
        String endPoint = uri.split("\\?")[0];
        System.out.println(endPoint);
        endPoint = endPoint.substring(0, endPoint.length());
        System.out.println(endPoint);
    }

    @Test
    public void test2() {
        String str = "http://12345/";
        str = str.substring(0, str.length());
        System.out.println(str);
    }
}
