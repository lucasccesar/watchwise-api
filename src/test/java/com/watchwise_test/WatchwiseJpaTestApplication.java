package com.watchwise_test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;

@SpringBootConfiguration(proxyBeanMethods = false)
@AutoConfigurationPackage(basePackages = "com.watchwise.watchwise_api")
public class WatchwiseJpaTestApplication {
}
