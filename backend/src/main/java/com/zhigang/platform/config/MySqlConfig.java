package com.zhigang.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("mysql")
@Configuration
@MapperScan("com.zhigang.platform.repository.mysql")
public class MySqlConfig {
}
