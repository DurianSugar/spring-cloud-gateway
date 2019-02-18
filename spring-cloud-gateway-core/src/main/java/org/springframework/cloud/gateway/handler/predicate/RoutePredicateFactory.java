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

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.support.Configurable;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.cloud.gateway.support.ShortcutConfigurable;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.toAsyncPredicate;

/**
 * @author Spencer Gibb
 * 是所有Predicate Factroy的顶级接口,用于生产Predicate
 */
//函数式接口,只允许有一个未实现的抽象方法
@FunctionalInterface
public interface RoutePredicateFactory<C> extends ShortcutConfigurable
		, Configurable<C> {//扩展Configurable接口,从接口命名上可以看出Predicate工厂是支持配置的,同时所有的PredicateFactroy都实现了该接口
	String PATTERN_KEY = "pattern";

	// useful for javadsl
	default Predicate<ServerWebExchange> apply(Consumer<C> consumer) {
		C config = newConfig();
		consumer.accept(config);
		beforeApply(config);
		return apply(config);
	}

	default AsyncPredicate<ServerWebExchange> applyAsync(Consumer<C> consumer) {
		C config = newConfig();
		consumer.accept(config);
		beforeApply(config);
		return applyAsync(config);
	}

	//获取配置类的类型,支持泛型,具体的config类型由子类指定
	@Override
	default Class<C> getConfigClass() {
		throw new UnsupportedOperationException("getConfigClass() not implemented");
	}

	//创建一个config实例,由具体的实现类来完成
	@Override
	default C newConfig() {
		throw new UnsupportedOperationException("newConfig() not implemented");
	}

	default void beforeApply(C config) {}

	//核心方法，即函数接口的唯一抽象方法，用于生产 Predicate，接收一个范型参数 config
	Predicate<ServerWebExchange> apply(C config);

	//对参数 config 应用工厂方法，并将返回结果 Predicate 包装成 AsyncPredicate。包装成 AsyncPredicate 是为了使用非阻塞模型
	default AsyncPredicate<ServerWebExchange> applyAsync(C config) {
		return toAsyncPredicate(apply(config));
	}

	/**
	 * 调用 {@link NameUtils#normalizeRoutePredicateName(Class)}方法,获得RoutePredicateFactory的名字,该方法截取类名前半段
	 * 例如 {@link PathRoutePredicateFactory}的结果为Path
	 * @return
	 */
	default String name() {
		return NameUtils.normalizeRoutePredicateName(getClass());
	}

}
