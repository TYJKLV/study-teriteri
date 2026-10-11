package com.teriteri.backend.config;

import com.teriteri.backend.config.filter.JwtAuthenticationTokenFilter;
import com.teriteri.backend.utils.ConstantsUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import javax.annotation.Resource;

@Slf4j
/**
 * 下面这两个注解，只开启一个就可以了：
 * 1. 必须有 @Configuration
 * 2. @EnableWebSecurity 中 有 @Configuration
 * 3. @EnableWebSecurity 作用之一：激活Spring Security的Web 安全功能，但在 Spring Boot + Security中默认开启了
 */
@Configuration
// @EnableWebSecurity  //可以往里面看到，引入一些.class文件，以及 其它的注解，目的：激活 Spring Security 的 Web 安全功能
public class SecurityConfig {

    @Resource  // 多个实现类，因此用这个
    private UserDetailsService userDetailsServiceImpl;

    @Resource
    private JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;


    /**
     * 使 JwtAuthenticationTokenFilter 不再注册到 Tomcat过滤链中，而只注册到 Spring Security链中
     * @param filter
     * @return
     * 作用机制：
     *   1. Spring Boot 启动时会扫描容器里所有 Filter 类型的 Bean，准备自动注册它们；
     *   2. 但它有个规矩：某个 Filter 如果已经被一张申请表（FilterRegistrationBean）包着了，就认为"这事已经有人管了"，跳过自动注册；
     *   3. 所以我们给它专门塞一张作废的申请表——表的存在让 Boot 不再自动注册它；而表自己是 disabled，也不会注册它。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationTokenFilter> jwtFilterRegistration(JwtAuthenticationTokenFilter filter){ // 自动注入方法参数
        FilterRegistrationBean<JwtAuthenticationTokenFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }


    /**
     * 密码BCrypt加密
     * @return BCrypt加密后的密码
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 用户名和密码验证
     * @return Authentication对象
     * 调用 authenticationProvider.authenticate()，便会走到这里
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                // 从Authentication对象中获取用户名和身份凭证信息
                String username = authentication.getName();
                String password = authentication.getCredentials().toString();

                UserDetails loginUser = userDetailsServiceImpl.loadUserByUsername(username);
                if (loginUser == null || !passwordEncoder().matches(password, loginUser.getPassword())) {
                    // 密码匹配失败抛出异常
                    throw new BadCredentialsException("访问拒绝：用户名或密码错误！");
                }

//                log.info("访问成功：" + loginUser);
                return new UsernamePasswordAuthenticationToken(loginUser, password, loginUser.getAuthorities());
                       // 该构造器，authenticated默认为 true，表示：认证完成
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return authentication.equals(UsernamePasswordAuthenticationToken.class);
            }
        };
    }

    /**
     * 请求接口过滤器，验证是否开放接口，如果不是开放接口请求头又没带 Authorization 属性会被直接拦截
     * @param http
     * @return
     * @throws Exception
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // 基于 token，不需要 csrf
                .csrf().disable()
                // 基于 token，不需要 session
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                // 下面开始设置权限
                .authorizeRequests(authorize -> authorize
                        // 请求放开接口  toArray()：将 Lise -> String数组   permitAll()：哪些路径是允许公开访问的
                        .antMatchers(ConstantsUtil.PUBLIC_PATHS.toArray(new String[0])).permitAll()
                        // 允许HTTP OPTIONS请求
                        .antMatchers(HttpMethod.OPTIONS).permitAll()
                        // 其他地址的访问均需验证权限
                        .anyRequest().authenticated()
                )
                // 添加 JWT 过滤器，JWT 过滤器在用户名密码认证过滤器之前
                .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
