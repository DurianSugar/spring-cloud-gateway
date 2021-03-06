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

/**
 * @author Spencer Gibb
 * 该接口同时继承了{@link RouteDefinitionLocator}和{@link RouteDefinitionWriter}
 * 只要实现该接口即可实现从存储器(例如,内存/redis/mysql等)读取,保存,删除路由配置
 * 目前gateway版本只实现了基于内存存储器的 {@link InMemoryRouteDefinitionRepository}
 */
public interface RouteDefinitionRepository extends RouteDefinitionLocator, RouteDefinitionWriter {
}
