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

package com.expediagroup.graphql.execution

import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.FetchedValue
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.SubscriptionExecutionStrategy
import graphql.execution.reactive.CompletionStageMappingPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import org.reactivestreams.Publisher
import java.util.Collections
import java.util.concurrent.CompletableFuture

/**
 * [SubscriptionExecutionStrategy] replacement that returns an [ExecutionResult]
 * that is a [Flow] instead of a [Publisher], and allows schema subscription functions
 * to return either a [Flow] or a [Publisher].
 *
 * Note this implementation is mostly a java->kotlin copy of [SubscriptionExecutionStrategy],
 * with [CompletionStageMappingPublisher] replaced by a [Flow] mapping, and [Flow] allowed
 * as an additional return type.  Any [Publisher]s returned will be converted to [Flow]s,
 * which may lose meaningful context information, so users are encouraged to create and
 * consume [Flow]s directly (see https://github.com/Kotlin/kotlinx.coroutines/issues/1825
 * https://github.com/Kotlin/kotlinx.coroutines/issues/1860 for some examples of lost context)
 */
class FlowSubscriptionExecutionStrategy(dfe: DataFetcherExceptionHandler) : ExecutionStrategy(dfe) {
    constructor() : this(SimpleDataFetcherExceptionHandler())

    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters
    ): CompletableFuture<ExecutionResult> {

        val sourceEventStream = createSourceEventStream(executionContext, parameters)

        //
        // when the upstream source event stream completes, subscribe to it and wire in our adapter
        return sourceEventStream.thenApply { sourceFlow ->
            if (sourceFlow == null) {
                ExecutionResultImpl(null, executionContext.errors)
            } else {
                val returnFlow = sourceFlow.map {
                    executeSubscriptionEvent(executionContext, parameters, it).await()
                }
                ExecutionResultImpl(returnFlow, executionContext.errors)
            }
        }
    }

    /*
        https://github.com/facebook/graphql/blob/master/spec/Section%206%20--%20Execution.md

        CreateSourceEventStream(subscription, schema, variableValues, initialValue):

            Let {subscriptionType} be the root Subscription type in {schema}.
            Assert: {subscriptionType} is an Object type.
            Let {selectionSet} be the top level Selection Set in {subscription}.
            Let {rootField} be the first top level field in {selectionSet}.
            Let {argumentValues} be the result of {CoerceArgumentValues(subscriptionType, rootField, variableValues)}.
            Let {fieldStream} be the result of running {ResolveFieldEventStream(subscriptionType, initialValue, rootField, argumentValues)}.
            Return {fieldStream}.
     */
    private fun createSourceEventStream(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters
    ): CompletableFuture<Flow<*>> {
        val newParameters = firstFieldOfSubscriptionSelection(parameters)

        val fieldFetched = fetchField(executionContext, newParameters)
        return fieldFetched.thenApply { fetchedValue ->
            val flow = when (val publisherOrFlow = fetchedValue.fetchedValue) {
                is Publisher<*> -> publisherOrFlow.asFlow()
                is Flow<*> -> publisherOrFlow
                else -> null
            }
            flow
        }
    }

    /*
        ExecuteSubscriptionEvent(subscription, schema, variableValues, initialValue):

        Let {subscriptionType} be the root Subscription type in {schema}.
        Assert: {subscriptionType} is an Object type.
        Let {selectionSet} be the top level Selection Set in {subscription}.
        Let {data} be the result of running {ExecuteSelectionSet(selectionSet, subscriptionType, initialValue, variableValues)} normally (allowing parallelization).
        Let {errors} be any field errors produced while executing the selection set.
        Return an unordered map containing {data} and {errors}.

        Note: The {ExecuteSubscriptionEvent()} algorithm is intentionally similar to {ExecuteQuery()} since this is how each event result is produced.
     */

    private fun executeSubscriptionEvent(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
        eventPayload: Any?
    ): CompletableFuture<ExecutionResult> {
        val newExecutionContext = executionContext.transform { builder -> builder.root(eventPayload) }

        val newParameters = firstFieldOfSubscriptionSelection(parameters)
        val fetchedValue = FetchedValue.newFetchedValue().fetchedValue(eventPayload)
            .rawFetchedValue(eventPayload)
            .localContext(parameters.localContext)
            .build()
        return completeField(newExecutionContext, newParameters, fetchedValue).fieldValue
            .thenApply { executionResult -> wrapWithRootFieldName(newParameters, executionResult) }
    }

    private fun wrapWithRootFieldName(
        parameters: ExecutionStrategyParameters,
        executionResult: ExecutionResult
    ): ExecutionResult {
        val rootFieldName = getRootFieldName(parameters)
        return ExecutionResultImpl(
            Collections.singletonMap<String, Any>(rootFieldName, executionResult.getData<Any>()),
            executionResult.errors
        )
    }

    private fun getRootFieldName(parameters: ExecutionStrategyParameters): String {
        val rootField = parameters.field.singleField
        return if (rootField.alias != null) rootField.alias else rootField.name
    }

    private fun firstFieldOfSubscriptionSelection(
        parameters: ExecutionStrategyParameters
    ): ExecutionStrategyParameters {
        val fields = parameters.fields
        val firstField = fields.getSubField(fields.keys[0])

        val fieldPath = parameters.path.segment(ExecutionStrategy.mkNameForPath(firstField.singleField))
        return parameters.transform { builder -> builder.field(firstField).path(fieldPath) }
    }
}
