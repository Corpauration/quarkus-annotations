package fr.corpauration.utils

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.visitor.KSValidateVisitor
import java.io.OutputStream
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


fun OutputStream.appendText(str: String) {
    this.write(str.toByteArray())
}

fun KSNode.validate2(predicate: (KSNode?, KSNode) -> Boolean = { _, _ -> true }): Boolean {
    class CustomValidator(predicate: (KSNode?, KSNode) -> Boolean) : KSValidateVisitor(predicate) {
        override fun visitTypeReference(typeReference: KSTypeReference, data: KSNode?): Boolean {
            return true
        }

        override fun visitValueArgument(valueArgument: KSValueArgument, data: KSNode?): Boolean {
            return true
        }
    }


    return this.accept(CustomValidator(predicate), null)
}

class RepositoryGeneratorProcessor(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger
) : SymbolProcessor {

    lateinit var customSql: Sequence<KSAnnotated>
    override fun process(resolver: Resolver): List<KSAnnotated> {
        customSql = resolver.getSymbolsWithAnnotation("fr.corpauration.utils.CustomSql")
        val symbols = resolver.getSymbolsWithAnnotation("fr.corpauration.utils.RepositoryGenerator")
        logger.warn("RepositoryGeneratorProcessor is running")
        symbols.forEach { action: KSAnnotated ->
            logger.warn("annotation found with ${action::class}", action.parent)
        }
        val ret = symbols.filter { !it.validate2() }.toList()
        symbols
            .filter { it is KSPropertyDeclaration/* && it.validate2() */ }
            .forEach {
                it.accept(RepositoryGeneratorVisitor(), it.annotations.find { predicate: KSAnnotation ->
                    predicate.shortName.asString() == "RepositoryGenerator"
                })
            }
        return ret
    }

    inner class RepositoryGeneratorVisitor : KSVisitor<KSAnnotation?, Unit> {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: KSAnnotation?) {
            logger.error("RepositoryGenerator should only be used on properties!", classDeclaration)
        }

        override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: KSAnnotation?) {
            val parent = property.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val className = property.simpleName.asString()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val table =
                data?.arguments!!.find { predicate: KSValueArgument -> predicate.name!!.asString() == "table" }?.value
            val id = data.arguments.find { predicate: KSValueArgument -> predicate.name!!.asString() == "id" }?.value
            val entity =
                data.arguments.find { predicate: KSValueArgument -> predicate.name!!.asString() == "entity" }?.value
            var additionalDataSource = data.arguments.find { predicate: KSValueArgument -> predicate.name!!.asString() == "additionalDataSource" }?.value
            if (additionalDataSource == "") additionalDataSource = null
            val entityProperties = ((entity!! as KSType).declaration as KSClassDeclaration).getAllProperties()
            val dbFields = ArrayList<String>()
            entityProperties.forEach {
                if (it.annotations.filter { it.shortName.asString() == "ManyToMany" || it.shortName.asString() == "Lazy" }
                        .count() == 0)
                    dbFields.add(it.simpleName.asString())
            }
            val lazyProprieties = ArrayList<String>()
            val lazyProprietiesMap = HashMap<String, String>()
            entityProperties.forEach {
                if (it.annotations.filter { it.shortName.asString() == "Lazy" }.count() == 1) {
                    lazyProprieties.add(it.simpleName.asString())
                    lazyProprietiesMap[it.simpleName.asString()] = it.type.toString()
                }
            }
            val oneToOneProprieties = ArrayList<String>()
            val oneToOneProprietiesMap = HashMap<String, String>()
            entityProperties.forEach {
                if (it.annotations.filter { it.shortName.asString() == "OneToOne" }.count() == 1) {
                    oneToOneProprieties.add(it.simpleName.asString())
                    oneToOneProprietiesMap[it.simpleName.asString()] =
                        "${it.type.resolve().declaration.packageName.asString()}.${it.type}"
                }
            }

            logger.warn("Size of customSql list: ${customSql.count()}")
            var customSqlAnnotations = customSql.map {
                it.annotations.find { predicate: KSAnnotation ->
                    predicate.shortName.asString() == "CustomSql"
                }
            }.filter {
                it?.arguments!!.find { predicate: KSValueArgument -> predicate.name!!.asString() == "entity" }?.value == entity
            }

            createFile(
                property,
                packageName,
                className,
                table,
                id,
                entity,
                dbFields,
                lazyProprieties,
                lazyProprietiesMap,
                oneToOneProprieties,
                oneToOneProprietiesMap,
                customSqlAnnotations,
                null
            )

            if (additionalDataSource != null) {
                createFile(
                    property,
                    packageName,
                    "${className}2",
                    table,
                    id,
                    entity,
                    dbFields,
                    lazyProprieties,
                    lazyProprietiesMap,
                    oneToOneProprieties,
                    oneToOneProprietiesMap,
                    customSqlAnnotations,
                    additionalDataSource as String
                )
            } else {
                createFile(
                    property,
                    packageName,
                    "${className}2",
                    table,
                    id,
                    entity,
                    dbFields,
                    lazyProprieties,
                    lazyProprietiesMap,
                    oneToOneProprieties,
                    oneToOneProprietiesMap,
                    customSqlAnnotations,
                    "default"
                )
            }
        }

        fun createFile(
            property: KSPropertyDeclaration,
            packageName: String,
            className: String,
            table: Any?,
            id: Any?,
            entity: Any,
            dbFields: ArrayList<String>,
            lazyProprieties: ArrayList<String>,
            lazyProprietiesMap: HashMap<String, String>,
            oneToOneProprieties: ArrayList<String>,
            oneToOneProprietiesMap: HashMap<String, String>,
            customSqlAnnotations: Sequence<KSAnnotation?>,
            additionalDataSource: String?
        ) {
            val file =
                codeGenerator.createNewFile(Dependencies(true, property.containingFile!!), packageName, className)
            file.appendText(ClassBuilder(packageName, className)
                .addImport("javax.enterprise.context.ApplicationScoped")
                .addImport("javax.inject.Inject")
                .addImport("io.vertx.mutiny.pgclient.PgPool")
                .addImport("io.quarkus.reactive.datasource.ReactiveDataSource")
                .addImport("$packageName.from")
                .addImport("java.util.UUID")
                .set("table", table)
                .set("id", id)
                .set("entity", entity)
                .set("repository", className)
                .addClassAnotation("@ApplicationScoped")
//                .addConstructorProprieties("client", "PgPool")
                .addCompanion(
                    """
                    companion object {
                        lateinit var INSTANCE: $className
                        val TABLE = "$table"
                    }
                """.trimIndent()
                )
                .addInit(
                    """
                    init {
                        $className.INSTANCE = this
                    }
                """.trimIndent()
                )
                .addField(
                    """
                    @Inject
                    ${if (additionalDataSource != null && additionalDataSource != "default") """@ReactiveDataSource("$additionalDataSource") """ else ""}
                    lateinit var client: PgPool
                """.trimIndent()
                )
                .add { input: ClassBuilder -> generateGetAll(input, dbFields, additionalDataSource != null) }
                .add { input: ClassBuilder -> generateGetIds(input) }
                .add { input: ClassBuilder -> generateFindById(input, dbFields) }
                .add { input: ClassBuilder -> generateFindBy(input, dbFields, additionalDataSource != null) }
                .add { input: ClassBuilder ->
                    generateSave(
                        input,
                        lazyProprieties,
                        oneToOneProprieties,
                        oneToOneProprietiesMap,
                        additionalDataSource != null
                    )
                }
                .add { input: ClassBuilder -> generateUpdate(input, lazyProprieties, additionalDataSource != null) }
                .add { input: ClassBuilder -> generateDelete(input) }
                .add { input: ClassBuilder -> generateLoadLazy(input, lazyProprieties, lazyProprietiesMap) }
                .add { input: ClassBuilder -> generateCustomSqlQueries(input, customSqlAnnotations, additionalDataSource != null) }
                .build())

            file.close()
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: KSAnnotation?) {

        }

        fun generateGetAll(builder: ClassBuilder, dbFields: ArrayList<String>, add: Boolean): ClassBuilder {
            val fields = dbFields.toMutableList()
            fields.replaceAll { "\\\"$it\\\"" }
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction(
                    """
                fun getAll(): Multi<${builder.get("entity")}> {
                    val rowSet: Uni<RowSet<Row>> = client.query("SELECT ${fields.joinToString(", ")} FROM ${
                        builder.get(
                            "table"
                        )
                    }").execute()
                    return rowSet.onItem().transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                        Multi.createFrom().iterable(set)
                    }).flatMap { ${builder.get("entity")}.Companion.from${if (add) "2" else ""}(it as Row, client)!!.toMulti() }
                }
                """.trimIndent()
                )
        }

        fun generateGetIds(builder: ClassBuilder): ClassBuilder {
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction(
                    """
                fun getIds(): Multi<${builder.get("id")}> {
                    val rowSet: Uni<RowSet<Row>> = client.query("SELECT id FROM ${builder.get("table")}").execute()
                    return rowSet.onItem().transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                        Multi.createFrom().iterable(set)
                    }).onItem().transform(Function<Any, ${builder.get("id")}> { row: Any ->
                        (row as Row).getValue("id") as ${builder.get("id")}
                    })
                }
                """.trimIndent()
                )
        }

        fun generateFindById(builder: ClassBuilder, dbFields: ArrayList<String>): ClassBuilder {
            val fields = dbFields.toMutableList()
            fields.replaceAll { "\\\"$it\\\"" }
            return builder.addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.RowIterator")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("io.vertx.mutiny.sqlclient.Tuple")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction(
                    """
                    fun findById(id: ${builder.get("id")}): Uni<${builder.get("entity")}> {
                        return client.preparedQuery("SELECT ${fields.joinToString(", ")} FROM ${builder.get("table")} WHERE id = ${'$'}1").execute(Tuple.of(id)).onItem().transform(RowSet<Row>::iterator)
                        .flatMap{ if (it.hasNext()) ${builder.get("entity")}.from(it.next() as Row, client) else null }
                    }    
                    """.trimIndent()
                )
        }

        fun generateFindBy(builder: ClassBuilder, dbFields: ArrayList<String>, add: Boolean): ClassBuilder {
            val fields = dbFields.toMutableList()
            fields.replaceAll { "\\\"$it\\\"" }
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction(
                    """
                    fun findBy(value: Any?, field: String): Multi<${builder.get("entity")}> {
                        val rowSet: Uni<RowSet<Row>> = if (value != null) client.preparedQuery("SELECT * FROM ${
                        builder.get(
                            "table"
                        )
                    } WHERE \"${"\$field"}\" = $1").execute(Tuple.of(value))
                            else client.query("SELECT ${fields.joinToString(", ")} FROM ${builder.get("table")} WHERE \"${"\$field"}\" IS NULL").execute()
                        return rowSet.onItem().transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                            Multi.createFrom().iterable(set)
                        }).flatMap { ${builder.get("entity")}.Companion.from${if (add) "2" else ""}(it as Row, client)!!.toMulti() }
                    }    
                    """.trimIndent()
                )
        }

        fun generateSave(
            builder: ClassBuilder,
            lazyProprieties: ArrayList<String>,
            oneToOneProprieties: ArrayList<String>,
            oneToOneProprietiesMap: HashMap<String, String>,
            add: Boolean
        ): ClassBuilder {
            codeGenerator.generatedFile.forEach { logger.warn(it.nameWithoutExtension) }
            val line = codeGenerator.generatedFile
                .filter { it.nameWithoutExtension == "${builder.get("entity")}Generated" }
                .first()
                .bufferedReader()
                .useLines { lines: Sequence<String> ->
                    lines
                        .take(1)
                        .toList()
                        .first()
                }
            var l = Regex("\\[(.*)\\]").find(line)?.groupValues!![1].split(",").toTypedArray()
            l = l.plus(lazyProprieties)
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .add { builder1 ->
                    var b = builder1
                    oneToOneProprietiesMap.forEach { b = b.addImport(it.value) }
                    b
                }
                .addFunction("""
                    fun save(obj: ${builder.get("entity")}): Uni<Void> {
                        ${
                    if (lazyProprieties.size > 0) """
                                if (${
                        kotlin.run {
                            val ll = (lazyProprieties.toMutableList())
                            ll.replaceAll { "obj.$it == null" }
                            ll.joinToString(" || ")
                        }
                    }) throw Exception("Some lazy properties were not initialized")
                            """.trimIndent() else ""
                }
                        return Uni.combine().all().unis<Void>(
                            client.preparedQuery("INSERT INTO ${builder.get("table")} (${
                    l.plus(oneToOneProprieties).map { "\\\"$it\\\"" }.joinToString(", ")
                }) VALUES (${
                    kotlin.run {
                        val ll = l.plus(oneToOneProprieties).toMutableList()
                        ll.replaceAll { "$${ll.indexOf(it) + 1}" }
                        ll.joinToString(", ")
                    }
                })").execute(Tuple.from(listOf(${
                    kotlin.run {
                        val one = oneToOneProprieties.toMutableList()
                        one.replaceAll { "$it?.id" }
                        val ll = l.plus(one).toMutableList()
                        ll.replaceAll { "obj.$it" }
                        ll.joinToString(", ")
                    }
                }))),
                            obj.save${if (add) "2" else ""}(client)
                        ).discardItems()
                    }    
                    """.trimIndent())
        }

        fun generateUpdate(
            builder: ClassBuilder,
            lazyProprieties: ArrayList<String>,
            add: Boolean
        ): ClassBuilder {
            codeGenerator.generatedFile.forEach { logger.warn(it.nameWithoutExtension) }
            val line = codeGenerator.generatedFile
                .filter { it.nameWithoutExtension == "${builder.get("entity")}Generated" }
                .first()
                .bufferedReader()
                .useLines { lines: Sequence<String> ->
                    lines
                        .take(1)
                        .toList()
                        .first()
                }
            val l = Regex("\\[(.*)\\]")
                .find(line)?.groupValues!![1]
                .split(",")
                .toTypedArray()
                .filter { it != "id" }
                .plus(lazyProprieties)
            val ll = (l.toMutableList())
            ll.replaceAll { "\\\"$it\\\" = $${ll.indexOf(it) + 1}" }
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction("""
                    fun update(obj: ${builder.get("entity")}): Uni<Void> {
                        ${
                    if (lazyProprieties.size > 0) """
                                        if (${
                        kotlin.run {
                            val ll = (lazyProprieties.toMutableList())
                            ll.replaceAll { "obj.$it == null" }
                            ll.joinToString(" || ")
                        }
                    }) throw Exception("Some lazy properties were not initialized")
                                    """.trimIndent() else ""
                }
                        return Uni.combine().all().unis<Void>(
                        ${
                            if (ll.size > 0) {
                                """
                                    client.preparedQuery("UPDATE ${builder.get("table")} SET ${ll.joinToString(", ")} WHERE id = ${'$'}${l.size + 1}").execute(Tuple.from(listOf(${
                                    kotlin.run {
                                        val lll = l.toMutableList()
                                        lll.replaceAll { "obj.$it" }
                                        lll.add("obj.id")
                                        lll.joinToString(", ")
                                    }
                                    }))),
                                """.trimIndent()
                            } else ""
                        }
                            obj.save${if (add) "2" else ""}(client)
                        ).discardItems()
                    }    
                    """.trimIndent())
        }

        fun generateDelete(builder: ClassBuilder): ClassBuilder {
            return builder
                .addImport("io.smallrye.mutiny.Multi")
                .addImport("io.smallrye.mutiny.Uni")
                .addImport("io.vertx.mutiny.sqlclient.RowSet")
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .addImport("java.util.function.Function")
                .addImport("org.reactivestreams.Publisher")
                .addFunction(
                    """
                    fun delete(obj: ${builder.get("entity")}): Uni<Void> {
                        return Uni.combine().all().unis<Void>(
                            obj.delete(client),
                            client.preparedQuery("DELETE FROM ${builder.get("table")} WHERE id = $1").execute(Tuple.of(obj.id))
                        ).discardItems()
                    }    
                    """.trimIndent()
                )
        }

        private fun generateLoadLazy(
            builder: ClassBuilder,
            lazyProprieties: ArrayList<String>,
            lazyProprietiesMap: HashMap<String, String>
        ): ClassBuilder {
            return if (lazyProprieties.size == 0) builder
            else builder
                .addExtension("""
                    fun ${builder.get("entity")}.loadLazy(): Uni<${builder.get("entity")}> {
                        return Uni.combine().all().unis<${builder.get("entity")}>(${
                    kotlin.run {
                        val l = lazyProprieties.toMutableList()
                        l.replaceAll {
                            """
                                    ${builder.get("repository")}.INSTANCE.client.preparedQuery("SELECT \"$it\" FROM ${
                                builder.get(
                                    "table"
                                )
                            } WHERE id = $1").execute(Tuple.of(this.id)).onItem().transform(RowSet<Row>::iterator)
                                        .onItem().transform { if (it.hasNext()) {this.$it = (it.next() as Row).getValue("$it") as ${lazyProprietiesMap[it]}; this} else null }
                                """.trimIndent()
                        }
                        l.joinToString(", ")
                    }
                }).combinedWith {
                            ${
                    kotlin.run {
                        var str = ""
                        lazyProprieties.forEachIndexed { index, prop ->
                            str += "(it[0] as ${builder.get("entity")}).$prop = (it[$index] as ${builder.get("entity")}).$prop\n"
                        }
                        str
                    }
                }
                            return@combinedWith it[0] as ${builder.get("entity")}
                        }
                    }
                """.trimIndent())
        }

        private fun generateCustomSqlQueries(builder: ClassBuilder, annotations: Sequence<KSAnnotation?>, add: Boolean): ClassBuilder {
            var b = builder
            for (annotation in annotations) {
                if (annotation != null) {
                    val sql = annotation.arguments.find { predicate: KSValueArgument -> predicate.name!!.asString() == "sql" }?.value as String
                    val function = annotation.parent as KSFunctionDeclaration
                    val returnType = function.returnType?.resolve()
                    val parameters = function.parameters.map {
                        it.type.resolve()
                    }
                    parameters.forEach {
                        b = b.addImport(it.declaration.qualifiedName!!.asString())
                    }
                    val fParameters = parameters.mapIndexed { index, param -> "p$index: $param" }
                    logger.warn(returnType.toString())
                    b = b.addFunction("""
                        fun ${function.simpleName.asString()}(${fParameters.joinToString(", ")}): $returnType {
                            return client.${if (parameters.isEmpty()) "query" else "preparedQuery"}(${"\"\"\""}$sql${"\"\"\""}).execute(${if (parameters.isNotEmpty()) "Tuple.from(listOf(${List(
                        parameters.size) { index -> "p$index" }.joinToString(", ")}))" else ""})
                            .onItem()${
                                if (returnType.toString() == "Uni<Void>") ".transform { null }"
                                else if (function.returnType.toString() == "Uni") ".transform(RowSet<Row>::iterator).flatMap{ if (it.hasNext()) ${builder.get("entity")}).from(it.next() as Row, client) else null }"
                                else if (returnType.toString() == "Multi<String>") """
                                    .transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                                        Multi.createFrom().iterable(set)
                                    }).onItem().transform { (it as Row).getValue(0) as String }
                                """.trimIndent()
                                else """
                                    .transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                                        Multi.createFrom().iterable(set)
                                    }).flatMap { ${builder.get("entity")}.Companion.from${if (add) "2" else ""}(it as Row, client)!!.toMulti() }
                                """.trimIndent()
                            }
                        }
                    """.trimIndent())
                    .addImport("io.vertx.mutiny.sqlclient.*")
                    .addFunction("""
                        fun ${function.simpleName.asString()}WithTransaction(${fParameters.plus("tr: SqlConnection").joinToString(", ")}): $returnType {
                            return tr.${if (parameters.isEmpty()) "query" else "preparedQuery"}(${"\"\"\""}$sql${"\"\"\""}).execute(${if (parameters.isNotEmpty()) "Tuple.from(listOf(${List(
                        parameters.size) { index -> "p$index" }.joinToString(", ")}))" else ""})
                            .onItem()${
                        if (returnType.toString() == "Uni<Void>") ".transform { null }"
                        else if (function.returnType.toString() == "Uni") ".transform(RowSet<Row>::iterator).flatMap{ if (it.hasNext()) ${builder.get("entity")}).from(it.next() as Row, client) else null }"
                        else if (returnType.toString() == "Multi<String>") """
                                    .transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                                        Multi.createFrom().iterable(set)
                                    }).onItem().transform { (it as Row).getValue(0) as String }
                                """.trimIndent()
                        else """
                                    .transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                                        Multi.createFrom().iterable(set)
                                    }).flatMap { ${builder.get("entity")}.Companion.from${if (add) "2" else ""}(it as Row, client)!!.toMulti() }
                                """.trimIndent()
                    }
                        }
                    """.trimIndent())
                }
            }
            return b
        }

        override fun visitAnnotated(annotated: KSAnnotated, data: KSAnnotation?) {

        }

        override fun visitAnnotation(annotation: KSAnnotation, data: KSAnnotation?) {

        }

        override fun visitCallableReference(reference: KSCallableReference, data: KSAnnotation?) {

        }

        override fun visitClassifierReference(reference: KSClassifierReference, data: KSAnnotation?) {

        }

        override fun visitDeclaration(declaration: KSDeclaration, data: KSAnnotation?) {

        }

        override fun visitDeclarationContainer(declarationContainer: KSDeclarationContainer, data: KSAnnotation?) {

        }

        override fun visitDynamicReference(reference: KSDynamicReference, data: KSAnnotation?) {

        }

        override fun visitFile(file: KSFile, data: KSAnnotation?) {

        }

        override fun visitModifierListOwner(modifierListOwner: KSModifierListOwner, data: KSAnnotation?) {

        }

        override fun visitNode(node: KSNode, data: KSAnnotation?) {

        }

        override fun visitParenthesizedReference(reference: KSParenthesizedReference, data: KSAnnotation?) {

        }

        override fun visitPropertyAccessor(accessor: KSPropertyAccessor, data: KSAnnotation?) {

        }

        override fun visitPropertyGetter(getter: KSPropertyGetter, data: KSAnnotation?) {

        }

        override fun visitPropertySetter(setter: KSPropertySetter, data: KSAnnotation?) {

        }

        override fun visitReferenceElement(element: KSReferenceElement, data: KSAnnotation?) {

        }

        override fun visitTypeAlias(typeAlias: KSTypeAlias, data: KSAnnotation?) {

        }

        override fun visitTypeArgument(typeArgument: KSTypeArgument, data: KSAnnotation?) {

        }

        override fun visitTypeParameter(typeParameter: KSTypeParameter, data: KSAnnotation?) {

        }

        override fun visitTypeReference(typeReference: KSTypeReference, data: KSAnnotation?) {

        }

        override fun visitValueArgument(valueArgument: KSValueArgument, data: KSAnnotation?) {

        }

        override fun visitValueParameter(valueParameter: KSValueParameter, data: KSAnnotation?) {

        }
    }
}

class RepositoryGeneratorProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment
    ): SymbolProcessor {
        return RepositoryGeneratorProcessor(environment.codeGenerator, environment.logger)
    }
}