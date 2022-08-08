package fr.corpauration.utils

import java.util.Random

class ClassBuilder(val packageName: String, val className: String) {
    private val imports: ArrayList<String> = ArrayList()

    private val classAnnotations: ArrayList<String> = ArrayList()

    private val constructorProperties: HashMap<String, String> = HashMap()

    private val fields: ArrayList<String> = ArrayList()

    private val inits: ArrayList<String> = ArrayList()

    private val companions: ArrayList<String> = ArrayList()

    private val functions: ArrayList<String> = ArrayList()

    private val variables: HashMap<String, Any?> = HashMap()

    private val extensions: ArrayList<String> = ArrayList()

    fun addImport(classPath: String): ClassBuilder {
        imports.add("import $classPath")
        return this
    }

    fun addClassAnotation(annotation: String): ClassBuilder {
        classAnnotations.add(annotation)
        return this
    }

    fun addConstructorProprieties(propriety: String, type: String): ClassBuilder {
        constructorProperties[propriety] = type
        return this
    }

    fun addField(field: String): ClassBuilder {
        fields.add(field)
        return this
    }

    fun addInit(init: String): ClassBuilder {
        inits.add(init)
        return this
    }

    fun addCompanion(companion: String): ClassBuilder {
        companions.add(companion)
        return this
    }

    fun addFunction(function: String): ClassBuilder {
        functions.add(function)
        return this
    }

    fun addExtension(extension: String): ClassBuilder {
        extensions.add(extension)
        return this
    }

    fun add(function: (input: ClassBuilder) -> ClassBuilder): ClassBuilder {
        return function(this)
    }

    fun set(variable: String, value: Any?): ClassBuilder {
        variables[variable] = value
        return this
    }

    fun get(variable: String): Any? {
        return variables[variable]
    }

    fun build(): String = """
    package $packageName

    ${imports.distinct().joinToString("\n    ")}

    /*
        This class was generated with ${
            kotlin.run { 
                var str = ""
                val random = Random(System.currentTimeMillis())
                for (i in 0..8) str += ((random.nextDouble() * ('\u23FF' - '\u0021')).toInt() + '\u0021'.code).toChar()
                str
            }
        }
    */
    ${classAnnotations.joinToString("\n")}
    class $className${if (!constructorProperties.isEmpty()) {
        var str = "("
        constructorProperties.forEach { propriety, type -> str += "var $propriety: $type," }
        str += ")"
        str
    } else ""
    } {
        ${fields.joinToString("\n        ")}
        
        ${inits.joinToString("\n\n        ")}
        
        ${companions.joinToString("\n\n        ")}

        ${functions.joinToString("\n\n        ")}
    }
    
    ${extensions.joinToString("\n\n")}
    """.trimIndent()
}