/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.handler;

import java.util.function.Function;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_HANDLER_MAPPER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	private final FilteringWebHandler webHandler;
	private final RouteLocator routeLocator;
	private final Integer managmentPort;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties, Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		if (environment.containsProperty("management.server.port")) {
			managmentPort = new Integer(environment.getProperty("management.server.port"));
		} else {
			managmentPort = null;
		}
		//调用 #setOrder(1) 的原因，Spring Cloud Gateway 的 GatewayWebfluxEndpoint 提供 HTTP API ，不需要经过网关，它通过 RequestMappingHandlerMapping 进行请求匹配处理。
		// RequestMappingHandlerMapping 的 order = 0 ，需要排在 RoutePredicateHandlerMapping 前面。所有，RoutePredicateHandlerMapping 设置 order = 1
		setOrder(1);		
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	/**
	 * {@link #getHandlerInternal(ServerWebExchange)}方法,在{@link org.springframework.web.reactive.DispatcherHandler#handle(ServerWebExchange)}方法中67行被调用
	 * 匹配Route,并返回处理Route的FilteringWebHandler
	 */
	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on the management port if set
		if (managmentPort != null && exchange.getRequest().getURI().getPort() == managmentPort.intValue()) {
			return Mono.empty();
		}
		// 设置GATEWAY_HANDLER_MAPPER_ATTR为RoutePredicateHandlerMapping
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

		// 调用lookupRoute方法,匹配Route
		return lookupRoute(exchange)
				// .log("route-predicate-handler-mapping", Level.FINER) //name this
				.flatMap((Function<Route, Mono<?>>) r -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isDebugEnabled()) {
						logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}
					// 设置GATEWAY_ROUTE_ATTR为匹配的Route
					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					//返回FilteringWebHandler
					return Mono.just(webHandler);

				//匹配不到 Route ，返回 Mono.empty() ，即不返回处理器。这样会不会有问题？不会，在 DispatcherHandler#handle(ServerWebExchange) 方法
				// 我们可以看到，当没有合适的 Handler ，返回 Mono.error(No matching handler)
				}).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");
					}
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java	        
	    
		return super.getCorsConfiguration(handler, exchange);
	}

	//TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	/**
	 * 顺序匹配Route
	 */
	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {

		/**
		 * 调用{@link RouteLocator#getRoutes()}方法,获取全部的Route,调用{@link AsyncPredicate#apply(Object)} 方法,顺序匹配一个Route
		 */
		return this.routeLocator.getRoutes()
				//individually filter routes so that filterWhen error delaying is not a problem
				.concatMap(route -> Mono
						.just(route)
						.filterWhen(r -> {
							// add the current route we are testing
							exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
							return r.getPredicate().apply(exchange);
						})
						//instead of immediately stopping main flux due to error, log and swallow it
						.doOnError(e -> logger.error("Error applying predicate for route: "+route.getId(), e))
						.onErrorResume(e -> Mono.empty())
				)
				// .defaultIfEmpty() put a static Route not found
				// or .switchIfEmpty()
				// .switchIfEmpty(Mono.<Route>empty().log("noroute"))
				.next()
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				});

		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param route the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}
}
