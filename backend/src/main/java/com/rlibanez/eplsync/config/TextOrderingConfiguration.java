package com.rlibanez.eplsync.config;

import com.rlibanez.eplsync.ordering.TextCollationDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class TextOrderingConfiguration {
    @Bean static BeanPostProcessor textCollationConnections() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                return bean instanceof DataSource source && !(source instanceof TextCollationDataSource)
                    ? new TextCollationDataSource(source):bean;
            }
        };
    }
}
