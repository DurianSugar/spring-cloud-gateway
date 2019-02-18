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

package org.springframework.cloud.gateway.handler.predicate;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.springframework.web.server.ServerWebExchange;

import javax.validation.constraints.NotNull;

/**
 * @author Spencer Gibb
 * Route 匹配: 请求时间满足在配置时间之后
 */
public class AfterRoutePredicateFactory extends AbstractRoutePredicateFactory<AfterRoutePredicateFactory.Config> {

	public static final String DATETIME_KEY = "datetime";

	public AfterRoutePredicateFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Collections.singletonList(DATETIME_KEY);
	}

	//生产 Predicate 对象，逻辑是判断当前时间（执行时）是否在 Config 中指定的 datetime之后
	@Override
	public Predicate<ServerWebExchange> apply(Config config) {
		ZonedDateTime datetime = config.getDatetime();
		// 通过 Lambda 表达式调用
		// Lambda 表达式实际上是一个对象。
		// 我们可以将 Lambda 表达式赋值给一个变量，就可像其它对象一样调用。
		// 这里实际上只是在这个方法体重重写了接口,并没有实际调用
		// 这里的return出来的对象,上一个方法接受到这个对象之后即可使用该重写的方法
		return exchange -> {
			final ZonedDateTime now = ZonedDateTime.now();
			return now.isAfter(datetime);
		};
	}

	public static class Config {
		@NotNull
		private ZonedDateTime datetime;

		public ZonedDateTime getDatetime() {
			return datetime;
		}

		public void setDatetime(ZonedDateTime datetime) {
			this.datetime = datetime;
		}
	}

}
