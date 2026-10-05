package com.teriteri.backend.service.impl.user;

import com.teriteri.backend.pojo.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDetailsImpl implements UserDetails {

    private User user;


    //TODO  getAuthorities()有什么用
    /*
        本项目不使用 Spring Security 的角色注解来控制权限
        是  Spring Security 的权限抽象，通常有  ROLE_USER、ROLE_ADMIN

     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return null;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }


    // TODO 下面四个默认实现返回 false，为什么手动修改为 true，这四个有什么用
    // 我简单解释一下： 在当前代码中并没有使用到
    /*
    由于在 SecurityConfig.java中，手动配置了 @Bean，作为 AuthenticationProvider接口的实现类，导致
    默认的 实现类 没有注入到 该接口中，手动注入的实现类是： 匿名内部类
    默认的实现类是 DaoAuthenticationProvider，没有重写 authenticate()，所以会调用 父类 AbstractUserDetailsAuthenticationProvider 中的
    authenticate()，在这个 方法中，便会调用到 下面四个 方法

    调用时机：分为 三个， pre、密码匹配、post，用了 模版方法模式

    为什么手动修改为 true：
    因为，这四个方法的默认返回为 false，则表示：账号过期了、被锁定了、凭证过期了、没有启用，就会导致 authenticate()调用四个方法时，
    返回 false后，表示 该账号不可用了，自然就不会走下面的逻辑了，就结束了，反映在 网页端就是，用户登录失败
     */


    @Override  // 账号是否没有过期
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override  // 账号是否没有被锁定
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override  // 账号凭证是否没有过期
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override  // 账号是否启用
    public boolean isEnabled() {
        return true;
    }
}
