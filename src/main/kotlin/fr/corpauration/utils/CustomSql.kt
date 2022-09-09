package fr.corpauration.utils

import kotlin.reflect.KClass

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION)
annotation class CustomSql(val sql: String, val entity: KClass<*>)
