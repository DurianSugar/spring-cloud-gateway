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

package org.springframework.cloud.gateway.handler;

import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @author Ben Hale
 *
 * Predicate即为Route中定义部分,用于条件匹配
 */
public interface AsyncPredicate<T> extends Function<T, Publisher<Boolean>> {

	//与操作,即两个Predicate组成一个,需要同时满足
	default AsyncPredicate<T> and(AsyncPredicate<? super T> other) {
		Objects.requireNonNull(other, "other must not be null");

		return t -> Flux.zip(apply(t), other.apply(t))
				.map(tuple -> tuple.getT1() && tuple.getT2());
	}

	//取反操作,即对Predicate匹配结果取反
	default AsyncPredicate<T> negate() {
		return t -> Mono.from(apply(t)).map(b -> !b);
	}

	//或操作,即两个Predicate组成一个,只需要满足一个
	default AsyncPredicate<T> or(AsyncPredicate<? super T> other) {
		Objects.requireNonNull(other, "other must not be null");

		return t -> Flux.zip(apply(t), other.apply(t))
				.map(tuple -> tuple.getT1() || tuple.getT2());
	}

}