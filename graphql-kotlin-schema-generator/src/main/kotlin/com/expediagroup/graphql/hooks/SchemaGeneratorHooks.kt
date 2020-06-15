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

package com.expediagroup.graphql.hooks

import com.expediagroup.graphql.directives.KotlinDirectiveWiringFactory
import com.expediagroup.graphql.exceptions.EmptyInputObjectTypeException
import com.expediagroup.graphql.exceptions.EmptyInterfaceTypeException
import com.expediagroup.graphql.exceptions.EmptyMutationTypeException
import com.expediagroup.graphql.exceptions.EmptyObjectTypeException
import com.expediagroup.graphql.exceptions.EmptyQueryTypeException
import com.expediagroup.graphql.exceptions.EmptySubscriptionTypeException
import com.expediagroup.graphql.generator.GraphQLConceptType
import com.expediagroup.graphql.generator.extensions.getName
import com.expediagroup.graphql.generator.extensions.getPropertyName
import com.expediagroup.graphql.generator.extensions.isSubclassOf
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLSchemaElement
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import org.reactivestreams.Publisher
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KType

/**
 * Collection of all the hooks when generating a schema.
 *
 * Hooks are lifecycle events that are called and triggered while the schema is building
 * that allow users to customize the schema.
 */
interface SchemaGeneratorHooks {
    /**
     * Called before the final GraphQL schema is built.
     * This doesn't prevent the called from rebuilding the final schema using java-graphql's functionality
     */
    fun willBuildSchema(builder: GraphQLSchema.Builder): GraphQLSchema.Builder = builder

    /**
     * Called before using reflection to generate the graphql object type for the given KType.
     * This allows supporting objects that the caller does not want to use reflection on for special handling
     */
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun willGenerateGraphQLType(type: KType, inputType: Boolean): GraphQLType? = null

    /**
     * Called after using reflection to generate the graphql object type but before returning it to the schema builder.
     * This allows for modifying the type info, like description or directives
     */
    fun willAddGraphQLTypeToSchema(type: KType, generatedType: GraphQLType): GraphQLType = generatedType

    /**
     * Called before resolving a KType to the GraphQL type.
     * This allows for a custom resolver on how to extract wrapped values, like in a CompletableFuture.
     */
    fun willResolveMonad(type: KType): KType = type

    /**
     * Called when looking at the KClass superclasses to determine if it valid for adding to the generated schema.
     * If any filter returns false, it is rejected.
     */
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun isValidSuperclass(kClass: KClass<*>, graphQLConceptType: GraphQLConceptType): Boolean = true

    /**
     * Called when looking at the KClass properties to determine if it valid for adding to the generated schema.
     * If any filter returns false, it is rejected.
     */
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun isValidProperty(kClass: KClass<*>, property: KProperty<*>, graphQLConceptType: GraphQLConceptType): Boolean = true

    /**
     * Called when looking at the KClass functions to determine if it valid for adding to the generated schema.
     * If any filter returns false, it is rejected.
     */
    @Suppress("Detekt.FunctionOnlyReturningConstant")
    fun isValidFunction(kClass: KClass<*>, function: KFunction<*>, graphQLConceptType: GraphQLConceptType): Boolean = true

    /**
     * Called when looking at the subscription functions to determine if it is using a valid return type.
     * By default, graphql-java supports org.reactivestreams.Publisher in the subscription execution strategy.
     * If you want to provide a custom execution strategy, you may need to override this hook.
     *
     * NOTE: You will most likely need to also override the [willResolveMonad] hook to allow for your custom type to be generated.
     */
    fun isValidSubscriptionReturnType(kClass: KClass<*>, function: KFunction<*>): Boolean = function.returnType.isSubclassOf(Publisher::class)

    /**
     * Called after `willGenerateGraphQLType` and before `didGenerateGraphQLType`.
     * Enables you to change the wiring, e.g. apply directives to alter the target type.
     */
    fun onRewireGraphQLType(generatedType: GraphQLSchemaElement, coordinates: FieldCoordinates? = null, codeRegistry: GraphQLCodeRegistry.Builder? = null): GraphQLSchemaElement =
        wiringFactory.onWire(generatedType, coordinates, codeRegistry)

    /**
     * Called after wrapping the type based on nullity but before adding the generated type to the schema
     */
    @Suppress("Detekt.ThrowsCount")
    fun didGenerateGraphQLType(type: KType, generatedType: GraphQLType): GraphQLType {
        val unwrapped = GraphQLTypeUtil.unwrapNonNull(generatedType)
        return when {
            unwrapped is GraphQLInterfaceType && unwrapped.fieldDefinitions.isEmpty() -> throw EmptyInterfaceTypeException(ktype = type)
            unwrapped is GraphQLObjectType && unwrapped.fieldDefinitions.isEmpty() -> throw EmptyObjectTypeException(ktype = type)
            unwrapped is GraphQLInputObjectType && unwrapped.fieldDefinitions.isEmpty() -> throw EmptyInputObjectTypeException(ktype = type)
            else -> generatedType
        }
    }

    /**
     * Called after converting the function to a field definition but before adding to the query object to allow customization
     */
    fun didGenerateQueryField(kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition): GraphQLFieldDefinition = fieldDefinition

    /**
     * Called after converting the function to a field definition but before adding to the mutation object to allow customization
     */
    fun didGenerateMutationField(kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition): GraphQLFieldDefinition = fieldDefinition

    /**
     * Called after converting the function to a field definition but before adding to the subscription object to allow customization
     */
    fun didGenerateSubscriptionField(kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition): GraphQLFieldDefinition = fieldDefinition

    /**
     * Called after generating the Query object but before adding it to the schema.
     */
    fun didGenerateQueryObject(type: GraphQLObjectType): GraphQLObjectType = if (type.fieldDefinitions.isEmpty()) {
        throw EmptyQueryTypeException
    } else {
        type
    }

    /**
     * Called after generating the Mutation object but before adding it to the schema.
     */
    fun didGenerateMutationObject(type: GraphQLObjectType): GraphQLObjectType = if (type.fieldDefinitions.isEmpty()) {
        throw EmptyMutationTypeException
    } else {
        type
    }

    /**
     * Called after generating the Subscription object but before adding it to the schema.
     */
    fun didGenerateSubscriptionObject(type: GraphQLObjectType): GraphQLObjectType = if (type.fieldDefinitions.isEmpty()) {
        throw EmptySubscriptionTypeException
    } else {
        type
    }

    fun getParameterName(generatedType: GraphQLType, parameter: KParameter, inputType: Boolean = false): String? = parameter.getName()

    fun getPropertyName(prop: KProperty<*>, parentClass: KClass<*>): String? {
        return prop.getPropertyName(parentClass)
    }

    val wiringFactory: KotlinDirectiveWiringFactory
        get() = KotlinDirectiveWiringFactory()
}
