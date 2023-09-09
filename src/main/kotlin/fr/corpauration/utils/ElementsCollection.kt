package fr.corpauration.utils

import kotlin.reflect.KClass

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class ElementsCollection(
    val junction_table: String,
    val main_column: String = "id",
    val key_column: String = "key",
    val key_type: KClass<*>,
    val value_column: String = "value",
    val value_type: KClass<*>
)
