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

package com.expediagroup.graphql.generator.types

import com.expediagroup.graphql.TopLevelNames
import com.expediagroup.graphql.TopLevelObject
import com.expediagroup.graphql.exceptions.EmptySubscriptionTypeException
import com.expediagroup.graphql.exceptions.InvalidSubscriptionTypeException
import com.expediagroup.graphql.generator.GraphQLConceptType
import com.expediagroup.graphql.generator.extensions.getTypeOfFirstArgument
import com.expediagroup.graphql.generator.extensions.isSubclassOf
import com.expediagroup.graphql.hooks.SchemaGeneratorHooks
import graphql.schema.GraphQLFieldDefinition
import io.mockk.every
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.reactivestreams.Publisher
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class GenerateSubscriptionTest : TypeTestHelper() {

    @Test
    fun `given an empty list, it should not return a field`() {
        val result = generateSubscriptions(generator, emptyList())
        assertNull(result)
    }

    @Test
    fun `given subscription without fields should throw exception`() {
        assertThrows<EmptySubscriptionTypeException> {
            generateSubscriptions(generator, listOf(TopLevelObject(MyEmptyTestSubscription())))
        }
    }

    @Test
    fun `give a valid subscription class, it should properly set the top level name`() {
        val subscriptions = listOf(TopLevelObject(MyPublicTestSubscription()))
        every { config.topLevelNames } returns TopLevelNames(subscription = "FooBar")
        val result = generateSubscriptions(generator, subscriptions)

        assertEquals(expected = "FooBar", actual = result?.name)
    }

    @Test
    fun `given a private class, it should throw an exception`() {
        val subscriptions = listOf(TopLevelObject(MyPrivateTestSubscription()))

        assertFailsWith(InvalidSubscriptionTypeException::class) {
            generateSubscriptions(generator, subscriptions)
        }
    }

    @Test
    fun `given a class with a function that does not return Publisher, it should throw an exception`() {
        val subscriptions = listOf(TopLevelObject(MyInvalidSubscriptionClass()))

        assertFailsWith(InvalidSubscriptionTypeException::class) {
            generateSubscriptions(generator, subscriptions)
        }
    }

    @Test
    fun `given a function that returns a Publisher, it should add it to the schema`() {
        val subscriptions = listOf(TopLevelObject(MyPublicTestSubscription()))

        val result = generateSubscriptions(generator, subscriptions)

        assertEquals(3, result?.fieldDefinitions?.size)
        assertNotNull(result?.fieldDefinitions?.find { it.name == "counter" })
        assertNotNull(result?.fieldDefinitions?.find { it.name == "flowabelCounter" })
        assertNotNull(result?.fieldDefinitions?.find { it.name == "filterMe" })
    }

    @Test
    fun `given custom hooks that filter functions, it should not generate those functions`() {
        val subscriptions = listOf(TopLevelObject(MyPublicTestSubscription()))

        class CustomHooks : SchemaGeneratorHooks {
            override fun isValidFunction(kClass: KClass<*>, function: KFunction<*>, graphQLConceptType: GraphQLConceptType): Boolean = function.name != "filterMe"
        }

        every { config.hooks } returns CustomHooks()

        val result = generateSubscriptions(generator, subscriptions)

        assertEquals(2, result?.fieldDefinitions?.size)
        assertNotNull(result?.fieldDefinitions?.find { it.name == "counter" })
    }

    @Test
    fun `given custom hooks that change the field after generation, it should use the new fields`() {
        val subscriptions = listOf(TopLevelObject(MyPublicTestSubscription()))

        class CustomHooks : SchemaGeneratorHooks {
            override fun didGenerateSubscriptionField(kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition): GraphQLFieldDefinition {
                return if (fieldDefinition.name == "filterMe") {
                    fieldDefinition.transform { fieldBuilder -> fieldBuilder.name("changedField") }
                } else fieldDefinition
            }
        }

        every { config.hooks } returns CustomHooks()

        val result = generateSubscriptions(generator, subscriptions)

        assertEquals(3, result?.fieldDefinitions?.size)
        assertNotNull(result?.fieldDefinitions?.find { it.name == "changedField" })
    }

    @Test
    fun `given custom hooks that allow custom subscription return types, it should generate a valid schema`() {
        val subscriptions = listOf(TopLevelObject(MyCustomSubscriptionClass()))

        class CustomHooks : SchemaGeneratorHooks {
            override fun isValidSubscriptionReturnType(kClass: KClass<*>, function: KFunction<*>) = function.returnType.isSubclassOf(Flow::class)
            override fun willResolveMonad(type: KType): KType = when {
                type.isSubclassOf(Flow::class) -> type.getTypeOfFirstArgument()
                else -> this.willResolveMonad(type)
            }
        }

        every { config.hooks } returns CustomHooks()

        val result = generateSubscriptions(generator, subscriptions)

        assertEquals(1, result?.fieldDefinitions?.size)
        assertNotNull(result?.fieldDefinitions?.find { it.name == "number" })
    }
}

class MyPublicTestSubscription {
    fun counter(): Publisher<Int> = Flowable.just(1)

    fun flowabelCounter(): Flowable<Int> = Flowable.just(1)

    fun filterMe(): Publisher<Int> = Flowable.just(2)
}

class MyInvalidSubscriptionClass {
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun number(): Int = 1
}

class MyCustomSubscriptionClass {
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun number(): Flow<Int> = flowOf(1)
}

private class MyPrivateTestSubscription {
    fun counter(): Publisher<Int> = Flowable.just(3)
}

class MyEmptyTestSubscription
