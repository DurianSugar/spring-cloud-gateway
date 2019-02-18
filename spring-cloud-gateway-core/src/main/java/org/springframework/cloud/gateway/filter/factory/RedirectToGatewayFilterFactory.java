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

package org.springframework.cloud.gateway.filter.factory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.support.HttpStatusHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setResponseStatus;

import reactor.core.publisher.Mono;

/**
 * @author Spencer Gibb
 */
public class RedirectToGatewayFilterFactory extends AbstractGatewayFilterFactory<RedirectToGatewayFilterFactory.Config> {

	public static final String STATUS_KEY = "status";
	public static final String URL_KEY = "url";

	public RedirectToGatewayFilterFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(STATUS_KEY, URL_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return apply(config.status, config.url);
	}

	public GatewayFilter apply(String statusString, String urlString) {
		//解析status,并判断是否是3XX重定向状态
		HttpStatusHolder httpStatus = HttpStatusHolder.parse(statusString);
		Assert.isTrue(httpStatus.is3xxRedirection(), "status must be a 3xx code, but was " + statusString);
		//创建URL
		final URI url = URI.create(urlString);
		return apply(httpStatus, url);
	}

	public GatewayFilter apply(HttpStatus httpStatus, URI uri) {
		return apply(new HttpStatusHolder(httpStatus, null), uri);
	}

	public GatewayFilter apply(HttpStatusHolder httpStatus, URI uri) {
		return (exchange, chain) ->
				//调用 #then(Mono) 方法，实现 After Filter 逻辑。这里和 AddRequestHeaderGatewayFilterFactory 实现的 Before Filter 【方式】不同。
				chain.filter(exchange).then(Mono.defer(() -> {
					//若响应为提交,设置响应的状态码和Header之后再返回
					if (!exchange.getResponse().isCommitted()) {
						//设置响应Status
						setResponseStatus(exchange, httpStatus);
						//设置响应Header
						final ServerHttpResponse response = exchange.getResponse();
						response.getHeaders().set(HttpHeaders.LOCATION, uri.toString());
						return response.setComplete();
					}
					//响应已提交
					return Mono.empty();
				}));
	}

	public static class Config {
		String status;
		String url;

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}
	}

}
