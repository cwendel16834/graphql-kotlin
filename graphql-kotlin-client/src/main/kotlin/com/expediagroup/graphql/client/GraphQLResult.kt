/*
 * Copyright 2020 Expedia, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.expediagroup.graphql.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * GraphQL operation result representation.
 *
 * @see [GraphQL Specification](http://spec.graphql.org/June2018/#sec-Response-Format) for additional details
 */
data class GraphQLResult<T>(
    /**
     * Field that represents all fields selected in the given operation or NULL if error was encountered before execution (or during execution if it prevents valid response).
     */
    val data: T? = null,
    /**
     * Optional field that contains a list of [GraphQLError] that were encountered during query execution
     */
    val errors: List<GraphQLError>? = null,
    /**
     * Optional field that contains arbitrary map of additional data that was populated during query execution (e.g. tracing or metrics information).
     */
    val extensions: Map<String, Any>? = null
)

/**
 * GraphQL error representation.
 *
 * @see [GraphQL Specification](http://spec.graphql.org/June2018/#sec-Errors) for additional details
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GraphQLError(
    /**
     * Description of the error.
     */
    val message: String,
    /**
     * List of locations within the GraphQL document at which the exception occurred.
     */
    val locations: List<SourceLocation> = emptyList(),
    /**
     * Path of the the response field that encountered the error.
     *
     * Path segments that represent fields should be strings, and path segments that represent list indices should be 0‐indexed integers. If the error happens in an aliased field, the path to the
     * error should use the aliased name, since it represents a path in the response, not in the query.
     */
    val path: List<Any>? = null,
    /**
     * Additional information about the error.
     */
    val extensions: Map<String, Any?>? = null
)

/**
 * Location describing which part of GraphQL document caused an exception.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceLocation(
    val line: Int,
    val column: Int
)
