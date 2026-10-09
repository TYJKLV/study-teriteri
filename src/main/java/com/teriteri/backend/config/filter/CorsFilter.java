package com.teriteri.backend.config.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


// 这个 Filter基本没用，若是 从安全性考虑，没有丝毫安全性；若是从可用性考虑，这段代码旨在解决 跨域问题，但 不存在跨域，因为 前端做了 处理
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class CorsFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        // TODO
        // 这里必须进行格式的转换吗，我不太理解
        HttpServletResponse response = (HttpServletResponse) res;
        HttpServletRequest request = (HttpServletRequest) req;

        // 浏览器跨域请求时，会自动带上这个头
        String origin = request.getHeader("Origin");
        if(origin!=null) {
            // 将 origin设置为 允许访问
            response.setHeader("Access-Control-Allow-Origin", origin);
        }

        // 触发时机：只有浏览器发送 OPTIONS 预检请求 时才会带 Access-Control-Request-Headers头
        String headers = request.getHeader("Access-Control-Request-Headers");
        if(headers!=null) {
            response.setHeader("Access-Control-Allow-Headers", headers);
            response.setHeader("Access-Control-Expose-Headers", headers);
        }

        response.setHeader("Access-Control-Allow-Methods", "*");
        response.setHeader("Access-Control-Max-Age", "3600");
        response.setHeader("Access-Control-Allow-Credentials", "true");


        // 上面的代码为 前置逻辑
        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) {

    }

    @Override
    public void destroy() {
    }
}

/**
 * 文件修改：
 * 1. 本质是一个 过滤器；因此，修改文件名从 CorsConfig -> CorsFilter
 * 2. 文件的注解由 @Configuration -> @Component
 */
