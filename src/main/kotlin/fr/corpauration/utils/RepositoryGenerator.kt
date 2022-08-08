package fr.corpauration.utils

import kotlin.reflect.KClass

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class RepositoryGenerator(val table: String, val id: KClass<*>, val entity: KClass<*>)
