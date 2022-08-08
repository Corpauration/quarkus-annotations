package fr.corpauration.utils

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate


class EntityProcessor(
    val codeGenerator: CodeGenerator,
    val logger: KSPLogger
) : SymbolProcessor {


    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("fr.corpauration.utils.Entity")
        logger.warn("EntityProcessor is running")
        symbols.forEach {
                action: KSAnnotated -> logger.warn("annotation found with ${action::class}", action.parent)
        }
        val ret = symbols.filter { !it.validate() }.toList()
        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .forEach { it.accept(EntityVisitor(), it.annotations.find {
                    predicate: KSAnnotation -> predicate.shortName.asString() == "Entity"
            }) }
        return ret
    }

    inner class EntityVisitor : KSVisitor<KSAnnotation?, Unit> {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: KSAnnotation?) {
            val packageName = classDeclaration.containingFile!!.packageName.asString()
            val className = classDeclaration.simpleName.asString() + "Generated"
            val properties = classDeclaration.getDeclaredProperties()
            val propertiesMap: HashMap<String, String> = HashMap()
            val toBeImported = ArrayList<String>()
            val manyToManyMeta = HashMap<String, HashMap<String, String>>()
            val oneToOneMeta = HashMap<String, HashMap<String, String>>()
            properties.forEach {
                if (it.annotations.find { it.shortName.asString() == "ManyToMany" } != null) {
                    val m = HashMap<String, String>()
                    m["type"] = it.type.element!!.typeArguments.first().type.toString()
                    m["import"] =
                        it.type.element!!.typeArguments.first().type!!.resolve().declaration.packageName.asString()
                    m["table"] =
                        it.annotations.find { it.shortName.asString() == "ManyToMany" }!!.arguments.find { it.name!!.asString() == "junction_table" }?.value as String
                    manyToManyMeta[it.simpleName.asString()] = m
                } else if (it.annotations.find { it.shortName.asString() == "OneToOne" } != null) {
                    val m = HashMap<String, String>()
                    m["type"] = it.type.toString()
                    m["import"] =
                        it.type.resolve().declaration.packageName.asString()
                    m["repository"] = if (m["type"]!!.contains("Entity")) m["type"]!!.replace("Entity", "Repository") else {logger.error("@OneToOne is not on an entity but on ${m["type"]}", classDeclaration); ""}
                    m["id"] = it.annotations.find { it.shortName.asString() == "OneToOne" }!!.arguments.find { it.name!!.asString() == "id" }?.value.toString()
                    oneToOneMeta[it.simpleName.asString()] = m
                } else if (it.annotations.find { it.shortName.asString() == "Lazy" } != null) {
//                    propertiesMap.put(it.simpleName.asString(), it.type.toString())
                } else {
                    propertiesMap.put(it.simpleName.asString(), it.type.toString() + if (it.type.resolve().isMarkedNullable) "?" else "")
                }

                if (it.type.resolve().declaration.packageName.asString() != "kotlin")
                    toBeImported.add("${it.type.resolve().declaration.packageName.asString()}.${it.type}")
            }
            val file = codeGenerator.createNewFile(Dependencies(true, classDeclaration.containingFile!!), packageName , className)
            file.appendText("""
                // [${propertiesMap.keys.joinToString(",")}]
                
            """.trimIndent())
            file.appendText(ClassBuilder(packageName, className)
                .addImport("io.vertx.mutiny.sqlclient.Row")
                .add { input: ClassBuilder -> generateExtensionManyToMany(input, classDeclaration.simpleName.asString(), manyToManyMeta) }
                .add { input: ClassBuilder -> generateExtensionOneToOne(input, classDeclaration.simpleName.asString(), oneToOneMeta) }
                .add { input: ClassBuilder -> generateExtension(input, propertiesMap, classDeclaration.simpleName.asString(), toBeImported, manyToManyMeta, oneToOneMeta) }
                .build())

            file.close()
        }

        override fun visitPropertyDeclaration(property: KSPropertyDeclaration, data: KSAnnotation?) {

        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: KSAnnotation?) {

        }

        fun generateExtension(
            builder: ClassBuilder,
            properties: HashMap<String, String>,
            originalClass: String,
            toBeImported: ArrayList<String>,
            manyToManyMeta: HashMap<String, HashMap<String, String>>,
            oneToOneMeta: HashMap<String, HashMap<String, String>>
        ): ClassBuilder {
            var b = builder
            var str = ""
            properties.forEach { name, clazz -> /*b = b.addImport(clazz);*/ str += "$name = row.getValue(\"$name\") as $clazz," }
            toBeImported.forEach { b = b.addImport(it) }
            return b
                .addImport("io.vertx.mutiny.pgclient.PgPool")
                .addImport("kotlinx.coroutines.launch")
                .addImport("kotlinx.coroutines.runBlocking")
                .addImport("kotlinx.coroutines.delay")
                .addImport("io.smallrye.mutiny.Uni")
                .addExtension("""
                    fun $originalClass.Companion.from(row: Row, client: PgPool): Uni<$originalClass> {
                        val o = $originalClass($str)
                        ${
                            kotlin.run {
                                var str2 = ""
                                oneToOneMeta.forEach { key, metadata ->
                                    str2 += "val $key = if(row.getValue(\"$key\") != null) ${metadata["repository"]}.INSTANCE.findById(row.getValue(\"$key\") as ${metadata["id"]}).onItem().transform { o.$key = it as ${metadata["type"]}; o } else Uni.createFrom().item(o)\n"
                                }
                                str2
                            }
                        }
                        return Uni.combine().all().unis<$originalClass>(Uni.createFrom().item(o)${
                            kotlin.run {
                                var str = ""
                                oneToOneMeta.keys.forEachIndexed { i, it -> str += ", $it" }
                                manyToManyMeta.keys.forEachIndexed { i, it -> str += ", o.load_$it(client)" }
                                str
                            }
                        }).combinedWith {
                            ${
                                kotlin.run {
                                    var str = ""
                                    var pad = oneToOneMeta.keys.size - 1
                                    oneToOneMeta.keys.forEachIndexed { i, it -> str += "(it[0] as $originalClass).$it = (it[${i + 1}] as $originalClass).$it\n" }
                                    manyToManyMeta.keys.forEachIndexed { i, it -> str += "(it[0] as $originalClass).$it = (it[${i + 1 + pad}] as $originalClass).$it\n" }
                                    str
                                }
                            }
                            return@combinedWith it[0] as $originalClass
                        }
                    }
                """.trimIndent())
                .addExtension("""
                    fun $originalClass.save(client: PgPool): Uni<Void> {
                        return Uni.combine().all().unis<Void>(Uni.createFrom().item<Int>(0) ${
                            kotlin.run {
                                var str = ""
                                manyToManyMeta.keys.forEachIndexed { i, it -> str += ", this.save_$it(client)" }
                                oneToOneMeta.keys.forEachIndexed { i, it -> str += ", this.save_$it(client)" }
                                str
                            }
                        }).discardItems()
                    }
                """.trimIndent())
                .addExtension("""
                    fun $originalClass.delete(client: PgPool): Uni<Void> {
                        return Uni.combine().all().unis<Void>(Uni.createFrom().item<Int>(0) ${
                    kotlin.run {
                        var str = ""
                        manyToManyMeta.keys.forEachIndexed { i, it -> str += ", this.delete_$it(client)" }
                        str
                    }
                }).discardItems()
                    }
                """.trimIndent())
        }

        fun generateExtensionManyToMany(
            builder: ClassBuilder,
            originalClass: String,
            manyToManyMeta: HashMap<String, HashMap<String, String>>
        ): ClassBuilder {
            var b = builder
            manyToManyMeta.forEach { prop, metadata ->
                b = b
                    .addImport("io.vertx.mutiny.pgclient.PgPool")
                    .addImport("io.smallrye.mutiny.coroutines.awaitSuspending")
                    .addImport("io.smallrye.mutiny.Multi")
                    .addImport("io.smallrye.mutiny.Uni")
                    .addImport("io.vertx.mutiny.sqlclient.RowSet")
                    .addImport("io.vertx.mutiny.sqlclient.Row")
                    .addImport("java.util.function.Function")
                    .addImport("org.reactivestreams.Publisher")
                    .addImport("io.vertx.mutiny.sqlclient.Tuple")
                    .addImport("${metadata["import"]!!}.${metadata["type"]}")
                    .addImport("${metadata["import"]!!}.from")
                    .addExtension(
                    """
                    fun $originalClass.load_$prop(client: PgPool): Uni<$originalClass> {
                        val rowSet: Uni<RowSet<Row>> = client.preparedQuery("SELECT o.* FROM ${metadata["table"]!!.split("_")[1]} AS o JOIN ${metadata["table"]} AS oo ON oo.ref = o.id WHERE oo.id = $1").execute(Tuple.of(id))
                        return rowSet.onItem().transformToMulti(Function<RowSet<Row>, Publisher<*>> { set: RowSet<Row> ->
                            Multi.createFrom().iterable(set)
                        }).flatMap { ${metadata["type"]}.from(it as Row, client).toMulti() }.collect().asList().onItem().transform { this.$prop = it.filterNotNull(); this }
                    }
                """.trimIndent())
                    .addExtension("""
                    fun $originalClass.save_$prop(client: PgPool): Uni<Void> {
                        return client.withTransaction{
                            val queries = ArrayList<Uni<RowSet<Row>>>()
                            queries.add(it.preparedQuery("DELETE FROM ${metadata["table"]} WHERE id = $1").execute(Tuple.of(this.id)))
                            this.$prop.forEach{ v -> queries.add(it.preparedQuery("INSERT INTO ${metadata["table"]} (id, ref) VALUES ($1, $2)").execute(Tuple.of(this.id, v.id))) }
                            Uni.combine().all().unis<RowSet<Row>>(queries).discardItems()
                        }
                    }
                    """.trimIndent())
                    .addExtension("""
                    fun $originalClass.delete_$prop(client: PgPool): Uni<Void> {
                        return client.withTransaction{
                            val queries = ArrayList<Uni<RowSet<Row>>>()
                            queries.add(it.preparedQuery("DELETE FROM ${metadata["table"]} WHERE id = $1").execute(Tuple.of(this.id)))
                            Uni.combine().all().unis<RowSet<Row>>(queries).discardItems()
                        }
                    }
                    """.trimIndent())
            }


            return b

        }

        fun generateExtensionOneToOne(
            builder: ClassBuilder,
            originalClass: String,
            oneToOneMeta: HashMap<String, HashMap<String, String>>
        ): ClassBuilder {
            var b = builder
            oneToOneMeta.forEach { prop, metadata ->
                b = b
                    .addImport("io.vertx.mutiny.pgclient.PgPool")
                    .addImport("io.smallrye.mutiny.coroutines.awaitSuspending")
                    .addImport("io.smallrye.mutiny.Multi")
                    .addImport("io.smallrye.mutiny.Uni")
                    .addImport("io.vertx.mutiny.sqlclient.RowSet")
                    .addImport("io.vertx.mutiny.sqlclient.Row")
                    .addImport("java.util.function.Function")
                    .addImport("org.reactivestreams.Publisher")
                    .addImport("io.vertx.mutiny.sqlclient.Tuple")
                    .addImport("${metadata["import"]!!}.${metadata["type"]}")
                    .addImport("${metadata["import"]!!}.${metadata["repository"]}")
                    .addImport("${metadata["import"]!!}.from")
                    .addExtension("""
                    fun $originalClass.save_$prop(client: PgPool): Uni<Void> {
                        return client.withTransaction{
                            val queries = ArrayList<Uni<RowSet<Row>>>()
                            queries.add(it.preparedQuery("UPDATE ${'$'}{${originalClass.replace("Entity", "Repository")}.TABLE} SET $prop = $1 WHERE id = $2").execute(Tuple.of(if (this.$prop != null) this.$prop!!.id else null, this.id)))
                            Uni.combine().all().unis<RowSet<Row>>(queries).discardItems()
                        }
                    }
                    """.trimIndent())
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

class EntityProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment
    ): SymbolProcessor {
        return EntityProcessor(environment.codeGenerator, environment.logger)
    }
}