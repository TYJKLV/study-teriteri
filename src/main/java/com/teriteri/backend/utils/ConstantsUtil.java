package com.teriteri.backend.utils;

import java.util.Arrays;
import java.util.List;

/**
 * @author tk
 * 常量工具类，统一管理 默认常量，方便修改
 */
public class ConstantsUtil {
    // 默认头像 URL
    public static final String USER_AVATAR_URL = "https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png";
    // 个人主页默认背景 URL
    public static final String USER_BG_URL = "https://tlvk-teriteri.oss-cn-hangzhou.aliyuncs.com/background/1sz3p8w2Sk%20.png";

    // 允许匿名访问的公开接口路径，与 SecurityConfig 的 permitAll 配置保持一致
    public static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/druid/**",
            "/favicon.ico",
            "/user/account/register",
            "/user/account/login",
            "/admin/account/login",
            "/category/getall",
            "/video/random/visitor",
            "/video/cumulative/visitor",
            "/video/getone",
            "/ws/danmu/**",
            "/danmu-list/**",
            "/msg/chat/outline",
            "/video/play/visitor",
            "/favorite/get-all/visitor",
            "/search/**",
            "/comment/get",
            "/comment/reply/get-more",
            "/comment/get-up-like",
            "/user/info/get-one",
            "/video/user-works-count",
            "/video/user-works",
            "/video/user-love",
            "/video/user-collect"
    );


    //JWT
    public static final long JWT_TTL = 60 * 60 * 24 * 2; //单位为 秒，则表示 2天
    // JWT_KEY: Base64编码后的字符
    public static final String JWT_KEY = "VGlhbll1SmllMjAwNTA4MzBpa194ejA4MDZrTG92ZU1l"; //44个，换算成 Base64，就是 264bit > 256bit
}
