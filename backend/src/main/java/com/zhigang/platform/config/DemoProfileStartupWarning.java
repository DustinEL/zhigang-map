package com.zhigang.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class DemoProfileStartupWarning implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DemoProfileStartupWarning.class);

    private final Environment environment;

    public DemoProfileStartupWarning(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String[] activeProfiles = environment.getActiveProfiles();
        String[] effectiveProfiles = activeProfiles.length > 0 ? activeProfiles : environment.getDefaultProfiles();
        boolean demoActive = Arrays.asList(effectiveProfiles).contains("demo");
        if (!demoActive) {
            return;
        }
        LOGGER.warn("当前以 demo 演示模式启动，/api/public/overview 等接口将返回虚构的演示数据");
        LOGGER.warn("如需评审、答辩或生产环境验证真实数据，请显式设置环境变量 SPRING_PROFILES_ACTIVE=mysql 后重新启动");
        LOGGER.warn("当前实际生效的 profile 列表：{}", Arrays.toString(effectiveProfiles));
    }
}
