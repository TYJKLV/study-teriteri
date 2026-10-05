package com.teriteri.backend.config.filter;

import com.teriteri.backend.pojo.User;
import com.teriteri.backend.service.impl.user.UserDetailsImpl;
import com.teriteri.backend.utils.ConstantsUtil;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

// 流程图在最下方
@Component
@Slf4j
public class JwtAuthenticationTokenFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RedisUtil redisUtil;

    // Spring框架中，用于路径匹配的核心类
    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 判断请求路径是否为允许匿名访问的公开接口
     * @param uri   请求路径
     * @return  true 是公开接口
     */
    private boolean isPublicPath(String uri) {
        for (String pattern : ConstantsUtil.PUBLIC_PATHS) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * token 认证过滤器，任何请求访问服务器都会先被这里拦截验证token合法性
     * @param request
     * @param response
     * @param filterChain
     * @throws ServletException
     * @throws IOException
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader("Authorization");

        if (!StringUtils.hasText(token) || !token.startsWith("Bearer ")) {
            // 通过开放接口过滤器后，如果没有可解析的token就放行
            filterChain.doFilter(request, response);
            return;
        }

        token = token.substring(7);  // 截掉 "Bearer "前缀，只留下token

        // 解析token
        boolean verifyToken = jwtUtil.verifyToken(token);
        if (!verifyToken) {
//            log.error("当前token已过期");
            if (isPublicPath(request.getRequestURI())) {
                // 公开接口携带了失效token时，以匿名身份放行
                filterChain.doFilter(request, response);
                return;
            }
            response.addHeader("message", "not login"); // 设置响应头信息，给前端判断用
            response.setStatus(403);
//            throw new AuthenticationException("当前token已过期");
            return;
        }
        String userId = JwtUtil.getSubjectFromToken(token);
        String channel = JwtUtil.getClaimFromToken(token, "channel");

        // 从redis中获取用户信息
        User user = redisUtil.getObject("security:" + channel + ":" + userId, User.class);

        if (user == null) {
            // 用户未登录，走下面逻辑
            if (isPublicPath(request.getRequestURI())) {
                // 公开接口携带了失效token时，以匿名身份放行
                filterChain.doFilter(request, response);
                return;
            }
            response.addHeader("message", "not login"); // 设置响应头信息，给前端判断用
            response.setStatus(403);
//            throw new AuthenticationException("用户未登录");
            return;
        }

        // 存入SecurityContextHolder，这里建议只供读取uid用，其中的状态等非静态数据可能不准，所以建议redis另外存值
        UserDetailsImpl loginUser = new UserDetailsImpl(user);
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(loginUser, null, null);
        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        // 放行
        filterChain.doFilter(request, response);
    }
}
/**
 * 请求进来
 *    │
 *    ▼
 * 取 Authorization 头
 *    │
 *    ├─ 没有 / 不是 Bearer 开头 ──► 直接放行 ──► 后续 Security 授权决定是否拦截
 *    │
 *    ▼
 * 截取 Bearer 后面的 JWT
 *    │
 *    ▼
 * 验证 JWT（签名、过期）
 *    │
 *    ├─ 无效 ──► 是公开接口？ ──是──► 放行（匿名）
 *    │              │
 *    │              └─否──► 返回 403 + message: not login
 *    │
 *    ▼
 * 从 JWT 取 userId、channel
 *    │
 *    ▼
 * 去 Redis 查 security:channel:userId
 *    │
 *    ├─ 没查到 ──► 是公开接口？ ──是──► 放行（匿名）
 *    │              │
 *    │              └─否──► 返回 403 + message: not login
 *    │
 *    ▼
 * 查到用户
 *    │
 *    ▼
 * 构建 Authentication 放入 SecurityContextHolder
 *    │
 *    ▼
 * 放行
 */


/**
 * public interface Authentication extends Principal, Serializable {
 *
 *     // 1. 权限列表
 *     Collection<? extends GrantedAuthority> getAuthorities();
 *
 *     // 2. 凭证（通常是密码，认证后应该擦除）
 *     Object getCredentials();
 *
 *     // 3. 用户主体（通常是 UserDetails）
 *     Object getPrincipal();
 *
 *     // 4. 是否已认证
 *     boolean isAuthenticated();
 *
 *     // 5. 设置认证状态
 *     void setAuthenticated(boolean isAuthenticated);
 * }
 */