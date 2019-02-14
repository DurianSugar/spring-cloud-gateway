package org.springframework.cloud.gateway.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Configuration;

/**
 * 用于判断项目是否正确导入spring-boot-starter-webflux依赖,而不是导入spring-boot-starter-web依赖,
 * 因为Spring Cloud GateWay2.X是基于webflux实现的
 */
@Configuration
@AutoConfigureBefore(GatewayAutoConfiguration.class)
public class GatewayClassPathWarningAutoConfiguration {

	private static final Log log = LogFactory.getLog(GatewayClassPathWarningAutoConfiguration.class);
	private static final String BORDER = "\n\n**********************************************************\n\n";

	@Configuration
	@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
	protected static class SpringMvcFoundOnClasspathConfiguration {

		public SpringMvcFoundOnClasspathConfiguration() {
			log.warn(BORDER+"Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway at this time. "+
					"Please remove spring-boot-starter-web dependency."+BORDER);
		}

	}

	@Configuration
	@ConditionalOnMissingClass("org.springframework.web.reactive.DispatcherHandler")
	protected static class WebfluxMissingFromClasspathConfiguration {

		public WebfluxMissingFromClasspathConfiguration() {
			log.warn(BORDER+"Spring Webflux is missing from the classpath, which is required for Spring Cloud Gateway at this time. "+
					"Please add spring-boot-starter-webflux dependency."+BORDER);
		}

	}
}
