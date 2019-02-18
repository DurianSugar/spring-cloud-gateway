/*
 * Copyright 2013-2018 the original author or authors.
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

import java.net.URI;
import java.util.List;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.Type;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_CONN_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CLIENT_RESPONSE_HEADER_NAMES;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.isAlreadyRouted;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setAlreadyRouted;

/**
 * @author Spencer Gibb
 * @author Biju Kunjummen
 * Netty路由网关过滤器,根据http://或者https://前缀过滤处理,使用基于Netty实现的HttpClient请求后端Http服务
 * {@link NettyRoutingFilter} 和 {@link NettyWriteResponseFilter}成对使用的网关过滤器,其将NettyRoutingFilter请求后端Http服务的响应写回客户端
 */
public class NettyRoutingFilter implements GlobalFilter, Ordered {

	private final HttpClient httpClient;
	private final ObjectProvider<List<HttpHeadersFilter>> headersFilters;
	private final HttpClientProperties properties;

	public NettyRoutingFilter(HttpClient httpClient,
							  ObjectProvider<List<HttpHeadersFilter>> headersFilters,
							  HttpClientProperties properties) {
		this.httpClient = httpClient;
		this.headersFilters = headersFilters;
		this.properties = properties;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 获得requestUrl
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		// 判断是否能够处理,1.该请求暂未被其他Routing网关处理,2.前缀是否正确
		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || (!"http".equals(scheme) && !"https".equals(scheme))) {
			return chain.filter(exchange);
		}
		// 设置该路由为已处理
		setAlreadyRouted(exchange);

		ServerHttpRequest request = exchange.getRequest();

		// 创建请求方法对象,Request Method
		final HttpMethod method = HttpMethod.valueOf(request.getMethodValue());
		final String url = requestUrl.toString();

		// 创建请求头,RequestHeader
		HttpHeaders filtered = filterRequest(this.headersFilters.getIfAvailable(),
				exchange);

		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		filtered.forEach(httpHeaders::set);

		String transferEncoding = request.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING);
		boolean chunkedTransfer = "chunked".equalsIgnoreCase(transferEncoding);

		boolean preserveHost = exchange.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);

		Flux<HttpClientResponse> responseFlux = this.httpClient
				.chunkedTransfer(chunkedTransfer)
				.request(method)
				.uri(url)
				.send((req, nettyOutbound) -> {
					req.headers(httpHeaders);

					if (preserveHost) {
						String host = request.getHeaders().getFirst(HttpHeaders.HOST);
						req.header(HttpHeaders.HOST, host);
					}
					return nettyOutbound
							.options(NettyPipeline.SendOptions::flushOnEach)
							.send(request.getBody().map(dataBuffer ->
									((NettyDataBuffer) dataBuffer).getNativeBuffer()));
				}).responseConnection((res, connection) -> {
					ServerHttpResponse response = exchange.getResponse();
					// put headers and status so filters can modify the response
					HttpHeaders headers = new HttpHeaders();

					res.responseHeaders().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));

					String contentTypeValue = headers.getFirst(HttpHeaders.CONTENT_TYPE);
					if (StringUtils.hasLength(contentTypeValue)) {
						exchange.getAttributes().put(ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR, contentTypeValue);
					}

					HttpStatus status = HttpStatus.resolve(res.status().code());
					if (status != null) {
						response.setStatusCode(status);
					} else if (response instanceof AbstractServerHttpResponse) {
						// https://jira.spring.io/browse/SPR-16748
						((AbstractServerHttpResponse) response).setStatusCodeValue(res.status().code());
					} else {
						throw new IllegalStateException("Unable to set status code on response: " + res.status().code() + ", " + response.getClass());
					}

					// make sure headers filters run after setting status so it is available in response
					HttpHeaders filteredResponseHeaders = HttpHeadersFilter.filter(
							this.headersFilters.getIfAvailable(), headers, exchange, Type.RESPONSE);

					if(!filteredResponseHeaders.containsKey(HttpHeaders.TRANSFER_ENCODING) &&
							filteredResponseHeaders.containsKey(HttpHeaders.CONTENT_LENGTH)) {
						//It is not valid to have both the transfer-encoding header and the content-length header
						//remove the transfer-encoding header in the response if the content-length header is presen
						response.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
					}

					exchange.getAttributes().put(CLIENT_RESPONSE_HEADER_NAMES, filteredResponseHeaders.keySet());

					response.getHeaders().putAll(filteredResponseHeaders);

					// Defer committing the response until all route filters have run
					// Put client response as ServerWebExchange attribute and write response later NettyWriteResponseFilter
					exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
					exchange.getAttributes().put(CLIENT_RESPONSE_CONN_ATTR, connection);

					return Mono.just(res);
				});

		if (properties.getResponseTimeout() != null) {
			responseFlux = responseFlux.timeout(properties.getResponseTimeout(),
					Mono.error(new TimeoutException("Response took longer than timeout: " +
							properties.getResponseTimeout()))).onErrorMap(TimeoutException.class, th -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, null, th));
		}

		return responseFlux.then(chain.filter(exchange));
	}

}
