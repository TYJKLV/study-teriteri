package com.teriteri.backend.service.impl.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.teriteri.backend.mapper.UserMapper;
import com.teriteri.backend.pojo.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 中间类，为 UserDetailsImpl提供服务
 *
 * SecurityConfig 中的 authenticate()手动调用 loadUserByUsername()，因为我们自己 new 了一个 AuthenticationProvider
 * 若 我们没有new 这个对象，那么 loadUserByUsername()也会被调用
 * 被 默认的 AuthenticationProvider实现类  DaoAuthenticationProvider 中的 retrieveUser()
 */

@Slf4j
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", username);
        queryWrapper.ne("state", 2);
        User user = userMapper.selectOne(queryWrapper); // 将 QueryWrapper 转换为 sql -> 数据库，并将返回的一行数据映射为 User对象
        // 若 返回结果 > 1行，报错
        if (user == null) {
            return null;
        }
        return new UserDetailsImpl(user);
    }
}
