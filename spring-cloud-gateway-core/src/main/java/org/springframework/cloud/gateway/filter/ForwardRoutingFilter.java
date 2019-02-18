package org.springframework.cloud.gateway.filter;

import java.net.URI;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * 根据Route配置的 forward://前缀进行过滤处理
 * ps.
 *   routes:
 * 	 - id: forward_sample
 * 	   uri: forward:///test
 * 	   predicates:
 *     - Path=/test
 * 	   filters:
 * 	   - PrefixPath=/gateway
 * 配置的 PrefixPathGatewayFilterFactory 将请求改写成 请求http://127.0.0.1:8080/test ==> http://127.0.0.1:8080/gateway/test
 * ForwardRoutingFilter 判断有 forward:// 前缀( Scheme )，过滤处理，将请求转发给 DispatcherHandler
 * DispatcherHandler 匹配并转发到当前网关实例本地接口 /gateway/test
 * 为什么需要配置 PrefixPathGatewayFilterFactory ？需要通过 PrefixPathGatewayFilterFactory 将请求重写路径，以匹配本地 API ，否则 DispatcherHandler 转发会失败
 */
public class ForwardRoutingFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(ForwardRoutingFilter.class);

	private final ObjectProvider<DispatcherHandler> dispatcherHandler;

	public ForwardRoutingFilter(ObjectProvider<DispatcherHandler> dispatcherHandler) {
		this.dispatcherHandler = dispatcherHandler;
	}

	@Override
	public int getOrder() {
		//返回 Integer.MAX_VALUE,即最大值,最后才去匹配
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		//获取requestUrl
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
		//判断是否可以处理,需要满足两个条件,1.前缀是forward: 2.调用 ServerWebExchangeUtils#isAlreadyRouted(ServerWebExchange) 方法，判断该请求暂未被其他 Routing 网关处理
		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || !"forward".equals(scheme)) {
			return chain.filter(exchange);
		}
		//设置为该请求已经被处理
		setAlreadyRouted(exchange);

		//TODO: translate url?

		if (log.isTraceEnabled()) {
			log.trace("Forwarding to URI: "+requestUrl);
		}
		//请求转发到DispatcherHandler
		return this.dispatcherHandler.getIfAvailable().handle(exchange);
	}
}
