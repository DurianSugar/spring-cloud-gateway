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

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.AsyncPredicate;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Flux;

/**
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 *	RouteDefinition转换成Route
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	public static final String DEFAULT_FILTERS = "defaultFilters";
	private final RouteDefinitionLocator routeDefinitionLocator;
	private final ConversionService conversionService;
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	/**
	 * @param routeDefinitionLocator 一个 RouteDefinitionLocator 对象,提供 RouteDefinition 的 RouteDefinitionLocator
	 * @param predicates             RoutePredicateFactory Bean 对象映射,Predicate 工厂列表，会被映射成 key 为 name, value 为 factory 的 Map。
	 *                               可以猜想出 gateway 是如何根据 PredicateDefinition 中定义的 name 来匹配到相对应的 factory 了
	 * @param gatewayFilterFactories RoutePredicateFactory Bean 对象映射,Gateway Filter 工厂列表，同样会被映射成 key 为 {@link GatewayFilterFactory#name()},
	 *                               value 为 factory 的 Map。具体可看FilterDefinition注释
	 * @param gatewayProperties      外部化配置类 , 使用 GatewayProperties.defaultFilters 默认过滤器定义数组
	 *
	 * 该类依赖 GatewayProperties 对象，后者已经携带了 List 结构的 RouteDefinition，那为什么还要依赖 RouteDefinitionLocator 来提供 RouteDefinition？
	 *	1.这里并不会直接使用到 GatewayProperties 类中的 RouteDefinition，仅是用到其定义的 default filters，这会应用到每一个 Route 上。
	 * 	2.最终传入的 RouteDefinitionLocator 实现上是 CompositeRouteDefinitionLocator 的实例，它组合了 GatewayProperties 中所定义的 routes。
	 */
	public RouteDefinitionRouteLocator(
			RouteDefinitionLocator routeDefinitionLocator,
			List<RoutePredicateFactory> predicates,
			List<GatewayFilterFactory> gatewayFilterFactories,
			GatewayProperties gatewayProperties,
			ConversionService conversionService) {
		//设置RouteDefinitionLocator
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.conversionService = conversionService;
		//初始化RoutePredicateFactory
		initFactories(predicates);
		//初始化映射
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named " + key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	/**
	 * 实现RouteLocator的getRoutest()方法
	 */
	@Override
	public Flux<Route> getRoutes() {
		/**
		 *	调用 {@link RouteDefinitionLocator#getRouteDefinitions()} 方法，获得 RouteDefinitions 数组
		 */
		return this.routeDefinitionLocator.getRouteDefinitions()
				.map(this::convertToRoute)//调用 convertToRoute 方法将 RouteDefinition 转换成 Route
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	/**
	 * 将每个RouteDefinition 转换成Route
	 * @param routeDefinition routeDefinition对象
	 * @return 转换后的route对象
	 */
	private Route convertToRoute(RouteDefinition routeDefinition) {
		//将 PredicateDefinition 转换成 AsyncPredicate。合并Predicate
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		//将 FilterDefinition 转换成 GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);
		//生成 Route 对象。
		return Route.async(routeDefinition)
				.asyncPredicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		//list.stream().map(T->R) 的作用是将list转出来的流中的每一个元素映射为R,类似于类型转换
		//具体可以看 https://cloud.tencent.com/developer/article/1187833
		List<GatewayFilter> filters = filterDefinitions.stream()
				.map(definition -> { // FilterDefinition => GatewayFilter
					//获得对应的GatewayFilterFactory的实现类AbstractGatewayFilterFactory的具体一个继承类,调用其apply方法
					GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
					if (factory == null) {
						throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
					}
					Map<String, String> args = definition.getArgs();
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
					}
					Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
					Object configuration = factory.newConfig();
					ConfigurationUtils.bind(configuration, properties, factory.shortcutFieldPrefix(),
							definition.getName(), validator, conversionService);
					GatewayFilter gatewayFilter = factory.apply(configuration);
					if (this.publisher != null) {
						this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
					}
					return gatewayFilter;
				})
				//转成List
				.collect(Collectors.toList());

		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			GatewayFilter gatewayFilter = filters.get(i);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			} else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}

	/**
	 *
	 */
	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		//TODO: support option to apply defaults after route specific filters?
		//添加默认过滤器,处理 GatewayProperties 中定义的默认的 FilterDefinition，转换成 GatewayFilter
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(DEFAULT_FILTERS,
					this.gatewayProperties.getDefaultFilters()));
		}

		//添加配置的过滤器,将 RouteDefinition 中定义的 FilterDefinition 转换成 GatewayFilter,这一步结束后过滤器已经具备默认和配置文件中配置的
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}
		// 对 GatewayFilter 进行排序，排序的详细逻辑请查阅 spring 中的 Ordered 接口
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private AsyncPredicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		//调用lookup,将列表中的第一个PredicateDefinition转换成AsyncPredicate
		AsyncPredicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));
		//只有当predicate大于1个的时候才会进行for循环,for循环还是转换成AsyncPredicate,但是执行and操作,将所有的AsyncPredicate组合成一个对象
		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			AsyncPredicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			predicate = predicate.and(found);
		}

		return predicate;
	}

	//AsyncPredicate转换逻辑
	@SuppressWarnings("unchecked")
	private AsyncPredicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		//根据predicate名称获取对应的PredicateFactory
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
			throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}
		//获取 PredicateDefinition 中的 Map 类型参数，key 是固定字符串_genkey_ + 数字拼接而成
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ args + " to " + predicate.getName());
		}

		//对上一个map获取到的参数进一步转换,key为 config 类（工厂类中通过范型指定）的属性名称
		Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
		//创建config对象
		Object config = factory.newConfig();
		//将properties绑定到config对象上
		ConfigurationUtils.bind(config, properties, factory.shortcutFieldPrefix(), predicate.getName(),
				validator, conversionService);
		if (this.publisher != null) {
			this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
		}
		//将 cofing 作参数代入，调用 factory 的 applyAsync 方法创建 AsyncPredicate 对象。
		return factory.applyAsync(config);
	}
}
