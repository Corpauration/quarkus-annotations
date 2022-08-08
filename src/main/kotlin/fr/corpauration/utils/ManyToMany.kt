package fr.corpauration.utils

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
annotation class ManyToMany(val junction_table: String)
