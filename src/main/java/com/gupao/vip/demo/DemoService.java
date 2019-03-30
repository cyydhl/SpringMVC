package com.gupao.vip.demo;

import com.gupao.vip.annotation.GPService;

/**
 * 核心业务逻辑
 */
@GPService
public class DemoService implements IDemoService {
    public String get(String name) {
        return "My name is " + name;
    }
}