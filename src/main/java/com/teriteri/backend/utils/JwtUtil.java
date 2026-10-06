package com.teriteri.backend.utils;

import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.teriteri.backend.utils.ConstantsUtil.JWT_KEY;
import static com.teriteri.backend.utils.ConstantsUtil.JWT_TTL;


/**
 * 生成 token、解析 token、校验 token
 */
@Component
@Slf4j
public class JwtUtil {
    @Autowired
    private RedisUtil redisUtil;

    // 有效期2天，记得修改 UserAccountServiceImpl 的 login 中redis的时间，注意单位，这里是毫秒
    // public static final long JWT_TTL = 60 * 60 * 24 * 2;
    // 旧的 JWT_KEY
    // public static final String JWT_KEY = "bEn2xiAnG0mU2TERITERI0YOu5HzH0hE1CwJ1GOnG1tOnG6kAifAwAnchEnG";

    public static String getUUID() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    /**
     * 获取token密钥
     * @return 加密后的token密钥
     */
    public static SecretKey getTokenSecret() {
        // 将 JWT_KEY经过 Base64解码获得 字节数组
        byte[] encodeKey = Base64.getDecoder().decode(JWT_KEY);
        return new SecretKeySpec(encodeKey, 0, encodeKey.length, "HmacSHA256");

        /*
        * 1.直接将一个符合 HS256算法长度的字符串 转为 字符数组作为 密钥不行吗？
        *   理论上是可行的，只不过不推荐而已
        *   相较于现在的模式，即 拿着一个被 Base64编码后的字符串，在 getTokenSecret()中经过 Base64解码，并转换为 字符数粗作为 密钥
        *   JWT_KEY 明面上是 无序的，随机的
         */

    }

    /**
     * 生成token
     * @param uid 用户id
     * @param channel 登录渠道 client/admin
     * @return token
     */
    public String createToken(String uid, String channel) {
        String uuid = getUUID();
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;
        SecretKey secretKey = getTokenSecret();
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        long expMillis = nowMillis + JWT_TTL * 1000L;
        Date expDate = new Date(expMillis);

        // 下面这么多的字段，除了 signWith外，其它都算是 PayLoad中的内容
        String token = Jwts.builder()
                .setId(uuid)    // 随机id，用于生成无规则token
                .setSubject(uid)    // 加密主体
                .claim("channel", channel)    // token登录渠道参数 user/admin 用于区分客户端和管理端，是自定义字段，其它是 jwt的标准字段
                .setIssuer("https://api.teriteri.fun")      // 发行方  都是用来验证token合法性的，可以不设置，
                .setAudience("https://www.teriteri.fun")    // 接收方  本项目也没有额外用来验证合法性的逻辑
                .signWith(secretKey, signatureAlgorithm)
                .setIssuedAt(now)
                .setExpiration(expDate)
                .compact();

        try {
            //缓存token信息，管理员和用户之间不要冲突                           单位是 ms，因此，要乘 1000，才等于 2天
            redisUtil.setExValue("token:" + channel + ":" + uid, token, JWT_TTL * 1000L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("存储redis数据异常", e);
        }
        return token;
    }

    /**
     * 获取Claims信息，Claims是一个 Map结构，里面的内容是 createToken() 中装进 payload的所有字段
     * 其余的 getXXFromToken, 都是调用 此方法，去获取到其中的某个字段
     * @param token
     * @return token的claims
     */
    public static Claims getAllClaimsFromToken(String token) {
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(getTokenSecret())
                    .build()
                    .parseClaimsJws(token)  //此方法内部，不仅会比对 签名，也会 校验过期时间
                    .getBody();
        } catch (ExpiredJwtException eje) {
            claims = null;
//            log.error("获取token信息异常，jwt已过期");
        } catch (Exception e) {
            claims = null;
//            log.error("获取token信息失败", e);
        }
        return claims;
    }


    /**
     * 校验传送来的token和缓存的token是否一致
     * @param token token
     * @return true/false
     */
    public boolean verifyToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        if (null == claims) {
            return false;
        }
        String uid = claims.getSubject();
        String channel;
        if (claims.containsKey("channel")) {
            channel = claims.get("channel").toString();
        } else {
            channel = "";
        }
        String cacheToken;
        try {
            cacheToken = String.valueOf(redisUtil.getValue("token:" + channel + ":" + uid));
        } catch (Exception e) {
            cacheToken = null;
            log.error("获取不到缓存的token", e);
        }
        return StringUtils.equals(token, cacheToken);
    }

    /**
     * 删除token，似乎用不到
     * @param token token
     * @param channel 登录渠道 client/admin
     */
    public void deleteToken(String token, String channel) {
        String uid;
        if (StringUtils.isNotEmpty(token)) {
            uid = getSubjectFromToken(token);
            try {
                redisUtil.delValue("token:" + channel + ":" + uid);
            } catch (Exception e) {
                log.error("删除redis数据异常", e);
            }
        }
    }

    /**
     * 获取token对应的UUID
     * @param token token
     * @return token对应的UUID
     */
    public static String getIdFromToken(String token) {
        String id = null;
        try {
            Claims claims = getAllClaimsFromToken(token);
            if (null != claims) {
                id = claims.getId();
            }
        } catch (Exception e) {
            log.error("从token里获取不到UUID", e);
        }
        return id;
    }

    /**
     * 获取发行人
     * @param token token
     * @return 发行人
     */
    public static String getIssuerFromToken(String token) {
        String issuer = null;
        try {
            Claims claims = getAllClaimsFromToken(token);
            if (null != claims) {
                issuer = claims.getIssuer();
            }
        } catch (Exception e) {
            log.error("从token里获取不到issuer", e);
        }
        return issuer;
    }

    /**
     * 获取token主题，即uid
     * @param token token
     * @return uid的字符串类型
     */
    public static String getSubjectFromToken(String token) {
        String subject;
        try {
            Claims claims = getAllClaimsFromToken(token);
            subject = claims.getSubject();
        } catch (Exception e) {
            subject = null;
            log.error("从token里获取不到主题", e);
        }
        return subject;
    }

    /**
     * 获取开始时间
     * @param token token
     * @return 开始时间
     */
    public static Date getIssuedDateFromToken(String token) {
        Date issueAt;
        try {
            Claims claims = getAllClaimsFromToken(token);
            issueAt = claims.getIssuedAt();
        } catch (Exception e) {
            issueAt = null;
            log.error("从token里获取不到开始时间", e);
        }
        return issueAt;
    }

    /**
     * 获取到期时间
     * @param token token
     * @return 到期时间
     */
    public static Date getExpirationDateFromToken(String token) {
        Date expiration;
        try {
            Claims claims = getAllClaimsFromToken(token);
            expiration = claims.getExpiration();
        } catch (Exception e) {
            expiration = null;
            log.error("从token里获取不到到期时间", e);
        }
        return expiration;
    }

    /**
     * 获取接收人
     * @param token token
     * @return 接收人
     */
    public static String getAudienceFromToken(String token) {
        String audience;
        try {
            Claims claims = getAllClaimsFromToken(token);
            audience = claims.getAudience();
        } catch (Exception e) {
            audience = null;
            log.error("从token里获取不到接收人", e);
        }
        return audience;
    }

    /**
     * 在token里获取对应参数的值
     * @param token token
     * @param param 参数名
     * @return 参数值
     */
    public static String getClaimFromToken(String token, String param) {
        Claims claims = getAllClaimsFromToken(token);
        if (null == claims) {
            return "";
        }
        if (claims.containsKey(param)) {
            return claims.get(param).toString();
        }
        return "";
    }
}
