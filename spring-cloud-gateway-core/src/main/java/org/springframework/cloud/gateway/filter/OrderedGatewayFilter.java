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

package org.springframework.cloud.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * @author Spencer Gibb
 * 有序的网关过滤器实现类,在FilterChain里,过滤器数组首先会按照order值升序排序,按照顺序过滤请求
 */
public class OrderedGatewayFilter implements GatewayFilter, Ordered {

	private final GatewayFilter delegate;
	private final int order;

	public OrderedGatewayFilter(GatewayFilter delegate, int order) {
		this.delegate = delegate;
		this.order = order;
	}

	public GatewayFilter getDelegate() {
		return delegate;
	}

	/**
	 * 使用delegate过滤请求
	 * @param exchange the current server exchange
	 * @param chain provides a way to delegate to the next filter
	 * @return
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return this.delegate.filter(exchange, chain);
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("OrderedGatewayFilter{");
		sb.append("delegate=").append(delegate);
		sb.append(", order=").append(order);
		sb.append('}');
		return sb.toString();
	}
}
