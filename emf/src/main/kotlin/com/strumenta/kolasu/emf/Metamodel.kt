package com.strumenta.kolasu.emf

import com.strumenta.kolasu.model.*
import com.strumenta.kolasu.validation.Result
import org.eclipse.emf.ecore.*
import org.eclipse.emf.ecore.resource.Resource
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.*
import kotlin.collections.HashMap
import kotlin.reflect.*
import kotlin.reflect.full.*

interface EDataTypeHandler {
    fun canHandle(ktype: KType): Boolean
    fun toDataType(ktype: KType): EDataType
    fun external(): Boolean
}

interface EClassTypeHandler {
    fun canHandle(ktype: KType): Boolean {
        return if (ktype.classifier is KClass<*>) {
            canHandle(ktype.classifier as KClass<*>)
        } else {
            false
        }
    }
    fun canHandle(kclass: KClass<*>): Boolean
    fun toEClass(kclass: KClass<*>, eClassProvider: ClassifiersProvider): EClass
    fun external(): Boolean
}

interface ClassifiersProvider {
    fun isDataType(ktype: KType): Boolean {
        return try {
            provideDataType(ktype)
            true
        } catch (e: Exception) {
            false
        }
    }
    fun provideClass(kClass: KClass<*>): EClass
    fun provideDataType(ktype: KType): EDataType?
}

class KolasuClassHandler(val kolasuKClass: KClass<*>, val kolasuEClass: EClass) : EClassTypeHandler {
    override fun canHandle(kclass: KClass<*>): Boolean = kclass == kolasuKClass

    override fun toEClass(kclass: KClass<*>, eClassProvider: ClassifiersProvider): EClass {
        return kolasuEClass
    }

    override fun external(): Boolean = true
}

class KolasuDataTypeHandler(val kolasuKClass: KClass<*>, val kolasuDataType: EDataType) : EDataTypeHandler {
    override fun canHandle(ktype: KType): Boolean {
        return ktype.classifier == kolasuKClass && ktype.arguments.isEmpty()
    }

    override fun toDataType(ktype: KType): EDataType {
        return kolasuDataType
    }

    override fun external(): Boolean = true
}

val LocalDateHandler = KolasuClassHandler(LocalDate::class, KOLASU_METAMODEL.getEClass("LocalDate"))
val LocalTimeHandler = KolasuClassHandler(LocalTime::class, KOLASU_METAMODEL.getEClass("LocalTime"))
val LocalDateTimeHandler = KolasuClassHandler(LocalDateTime::class, KOLASU_METAMODEL.getEClass("LocalDateTime"))

val NodeHandler = KolasuClassHandler(Node::class, KOLASU_METAMODEL.getEClass("ASTNode"))
val NamedHandler = KolasuClassHandler(Named::class, KOLASU_METAMODEL.getEClass("Named"))
val ReferenceByNameHandler = KolasuClassHandler(ReferenceByName::class, KOLASU_METAMODEL.getEClass("ReferenceByName"))
val ResultHandler = KolasuClassHandler(Result::class, KOLASU_METAMODEL.getEClass("Result"))

val StringHandler = KolasuDataTypeHandler(String::class, KOLASU_METAMODEL.getEClassifier("string") as EDataType)
val BooleanHandler = KolasuDataTypeHandler(Boolean::class, KOLASU_METAMODEL.getEClassifier("boolean") as EDataType)
val IntHandler = KolasuDataTypeHandler(Int::class, KOLASU_METAMODEL.getEClassifier("int") as EDataType)
val IntegerHandler = KolasuDataTypeHandler(Integer::class, KOLASU_METAMODEL.getEClassifier("int") as EDataType)
val BigIntegerHandler = KolasuDataTypeHandler(
    BigInteger::class,
    KOLASU_METAMODEL.getEClassifier(BigInteger::class.simpleName) as EDataType
)
val BigDecimalHandler = KolasuDataTypeHandler(
    BigDecimal::class,
    KOLASU_METAMODEL.getEClassifier(BigDecimal::class.simpleName) as EDataType
)
val LongHandler = KolasuDataTypeHandler(Long::class, KOLASU_METAMODEL.getEClassifier("long") as EDataType)

val KClass<*>.eClassifierName: String
    get() = this.java.eClassifierName

val Class<*>.eClassifierName: String
    get() = if (this.enclosingClass != null) {
        "${this.enclosingClass.simpleName}${this.simpleName}"
    } else {
        this.simpleName
    }

class MetamodelBuilder(packageName: String, nsURI: String, nsPrefix: String, resource: Resource? = null) :
    ClassifiersProvider {

    private val ePackage: EPackage = EcoreFactory.eINSTANCE.createEPackage()
    private val eClasses = HashMap<KClass<*>, EClass>()
    private val dataTypes = HashMap<KType, EDataType>()
    private val eclassTypeHandlers = LinkedList<EClassTypeHandler>()
    private val dataTypeHandlers = LinkedList<EDataTypeHandler>()

    init {
        ePackage.name = packageName
        ePackage.nsURI = nsURI
        ePackage.nsPrefix = nsPrefix
        if (resource == null) {
            ePackage.setResourceURI(nsURI)
        } else {
            resource.contents.add(ePackage)
            eclassTypeHandlers.add(ResourceClassTypeHandler(resource, ePackage))
        }

        dataTypeHandlers.add(StringHandler)
        dataTypeHandlers.add(BooleanHandler)
        dataTypeHandlers.add(IntHandler)
        dataTypeHandlers.add(IntegerHandler)
        dataTypeHandlers.add(LongHandler)
        dataTypeHandlers.add(BigIntegerHandler)
        dataTypeHandlers.add(BigDecimalHandler)

        eclassTypeHandlers.add(LocalDateHandler)
        eclassTypeHandlers.add(LocalTimeHandler)
        eclassTypeHandlers.add(LocalDateTimeHandler)

        eclassTypeHandlers.add(NodeHandler)
        eclassTypeHandlers.add(NamedHandler)
        eclassTypeHandlers.add(ReferenceByNameHandler)
        eclassTypeHandlers.add(ResultHandler)
    }

    /**
     * Normally a class is not treated as a DataType, so we need specific DataTypeHandlers
     * to recognize it as such
     */
    fun addDataTypeHandler(eDataTypeHandler: EDataTypeHandler) {
        dataTypeHandlers.add(eDataTypeHandler)
    }

    /**
     * This should be needed only to customize how we want to deal with a class when translating
     * it to an EClass
     */
    fun addEClassTypeHandler(eClassTypeHandler: EClassTypeHandler) {
        eclassTypeHandlers.add(eClassTypeHandler)
    }

    private fun createEEnum(kClass: KClass<out Enum<*>>): EEnum {
        val eEnum = EcoreFactory.eINSTANCE.createEEnum()
        eEnum.name = kClass.eClassifierName
        kClass.java.enumConstants.forEach {
            val eLiteral = EcoreFactory.eINSTANCE.createEEnumLiteral()
            eLiteral.name = it.name
            eLiteral.value = it.ordinal
            eEnum.eLiterals.add(eLiteral)
        }
        return eEnum
    }

    override fun provideDataType(ktype: KType): EDataType? {
        if (!dataTypes.containsKey(ktype)) {
            val eDataType: EDataType
            var external = false
            when {
                (ktype.classifier as? KClass<*>)?.isSubclassOf(Enum::class) == true -> {
                    eDataType = createEEnum(ktype.classifier as KClass<out Enum<*>>)
                }
                else -> {
                    val handler = dataTypeHandlers.find { it.canHandle(ktype) }
                    if (handler == null) {
                        // throw RuntimeException("Unable to handle data type $ktype, with classifier ${ktype.classifier}")\
                        return null
                    } else {
                        external = handler.external()
                        eDataType = handler.toDataType(ktype)
                    }
                }
            }
            if (!external) {
                ensureClassifierNameIsNotUsed(eDataType)
                ePackage.eClassifiers.add(eDataType)
            }
            dataTypes[ktype] = eDataType
        }
        return dataTypes[ktype]!!
    }

    private fun classToEClass(kClass: KClass<*>): EClass {
        if (kClass == Any::class) {
            return EcoreFactory.eINSTANCE.ecorePackage.eObject
        }

        val eClass = EcoreFactory.eINSTANCE.createEClass()
        // This is necessary because some classes refer to themselves
        registerKClassForEClass(kClass, eClass)

        kClass.superclasses.forEach {
            if (it != Any::class) {
                eClass.eSuperTypes.add(provideClass(it))
            }
        }
        eClass.name = kClass.eClassifierName

        eClass.isAbstract = kClass.isAbstract || kClass.isSealed
        eClass.isInterface = kClass.java.isInterface

        kClass.typeParameters.forEach { kTypeParameter: KTypeParameter ->
            eClass.eTypeParameters.add(
                // TODO consider bounds, taking in account that in Kotlin we have variance (in/out)
                // which may not exactly correspond to how bounds work in EMF
                EcoreFactory.eINSTANCE.createETypeParameter().apply {
                    name = kTypeParameter.name
                }
            )
        }

        kClass.processProperties { prop ->
            try {
                if (eClass.eAllStructuralFeatures.any { sf -> sf.name == prop.name }) {
                    // skip
                } else {
                    // do not process inherited properties
                    val valueType = prop.valueType
                    if (prop.provideNodes) {
                        registerReference(prop, valueType, eClass)
                    } else {
                        val nullable = prop.valueType.isMarkedNullable
                        val dataType = provideDataType(prop.valueType.withNullability(false))
                        if (dataType == null) {
                            // We can treat it like a class
                            registerReference(prop, valueType, eClass)
                        } else {
                            val ea = EcoreFactory.eINSTANCE.createEAttribute()
                            ea.name = prop.name
                            if (prop.multiple) {
                                ea.lowerBound = 0
                                ea.upperBound = -1
                            } else {
                                ea.lowerBound = if (nullable) 0 else 1
                                ea.upperBound = 1
                            }
                            ea.eType = dataType
                            eClass.eStructuralFeatures.add(ea)
                        }
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Issue processing property $prop in class $kClass", e)
            }
        }
        return eClass
    }

    private fun registerReference(
        prop: PropertyTypeDescription,
        valueType: KType,
        eClass: EClass
    ) {
        val ec = EcoreFactory.eINSTANCE.createEReference()
        ec.name = prop.name
        if (prop.multiple) {
            ec.lowerBound = 0
            ec.upperBound = -1
        } else {
            ec.lowerBound = 0
            ec.upperBound = 1
        }
        ec.isContainment = true
        // No type parameters on methods should be allowed elsewhere and only the type parameters
        // on the class should be visible. We are not expecting containing classes to expose
        // type parameters
        val visibleTypeParameters = eClass.eTypeParameters.associateBy { it.name }
        setType(ec, valueType, visibleTypeParameters)
        eClass.eStructuralFeatures.add(ec)
    }

    private fun provideType(valueType: KTypeProjection): EGenericType {
        when (valueType.variance) {
            KVariance.INVARIANT -> {
                return provideType(valueType.type!!)
            }
            else -> TODO("Variance ${valueType.variance} not yet sypported")
        }
    }

    private fun provideType(valueType: KType): EGenericType {
        val dataType = provideDataType(valueType.withNullability(false))
        if (dataType != null) {
            return EcoreFactory.eINSTANCE.createEGenericType().apply {
                eClassifier = dataType
            }
        }
        if (valueType.arguments.isNotEmpty()) {
            TODO("Not yet supported: type arguments in $valueType")
        }
        if (valueType.classifier is KClass<*>) {
            return EcoreFactory.eINSTANCE.createEGenericType().apply {
                eClassifier = provideClass(valueType.classifier as KClass<*>)
            }
        } else {
            TODO("Not yet supported: ${valueType.classifier}")
        }
    }

    private fun setType(
        element: ETypedElement,
        valueType: KType,
        visibleTypeParameters: Map<String, ETypeParameter>
    ) {
        when (val classifier = valueType.classifier) {
            is KClass<*> -> {
                if (classifier.typeParameters.isEmpty()) {
                    element.eType = provideClass(classifier)
                } else {
                    element.eGenericType = EcoreFactory.eINSTANCE.createEGenericType().apply {
                        eClassifier = provideClass(classifier)
                        require(classifier.typeParameters.size == valueType.arguments.size)
                        eTypeArguments.addAll(
                            valueType.arguments.map {
                                provideType(it)
                            }
                        )
                    }
                }
            }
            is KTypeParameter -> {
                element.eGenericType = EcoreFactory.eINSTANCE.createEGenericType().apply {
                    eTypeParameter = visibleTypeParameters[classifier.name]
                        ?: throw IllegalStateException("Type parameter not found")
                }
            }
            else -> throw Error("Not a valid classifier: $classifier")
        }
    }

    private fun ensureClassifierNameIsNotUsed(classifier: EClassifier) {
        if (ePackage.hasClassifierNamed(classifier.name)) {
            throw IllegalStateException(
                "There is already a Classifier named ${classifier.name}: ${ePackage.classifierByName(classifier.name)}"
            )
        }
    }

    private fun registerKClassForEClass(kClass: KClass<*>, eClass: EClass) {
        if (eClasses.containsKey(kClass)) {
            require(eClasses[kClass] == eClass)
        } else {
            eClasses[kClass] = eClass
        }
    }

    override fun provideClass(kClass: KClass<*>): EClass {
        if (!eClasses.containsKey(kClass)) {
            val ch = eclassTypeHandlers.find { it.canHandle(kClass) }
            val eClass = ch?.toEClass(kClass, this) ?: classToEClass(kClass)
            if (ch == null || !ch.external()) {
                ensureClassifierNameIsNotUsed(eClass)
                ePackage.eClassifiers.add(eClass)
            }
            registerKClassForEClass(kClass, eClass)
            if (kClass.isSealed) {
                kClass.sealedSubclasses.forEach {
                    queue.add(it)
                }
            }
        }
        while (queue.isNotEmpty()) {
            provideClass(queue.removeFirst())
        }
        return eClasses[kClass]!!
    }

    private val queue = LinkedList<KClass<*>>()

    fun generate(): EPackage {
        return ePackage
    }
}

class ResourceClassTypeHandler(val resource: Resource, val ownPackage: EPackage) : EClassTypeHandler {
    override fun canHandle(ktype: KClass<*>): Boolean = getPackage(packageName(ktype)) != null

    private fun getPackage(packageName: String): EPackage? =
        resource.contents.find { it is EPackage && it != ownPackage && it.name == packageName } as EPackage?

    override fun toEClass(kclass: KClass<*>, eClassProvider: ClassifiersProvider): EClass {
        return getPackage(packageName(kclass))!!.eClassifiers.find {
            it is EClass && it.name == kclass.simpleName
        } as EClass? ?: throw NoClassDefFoundError(kclass.qualifiedName)
    }

    override fun external(): Boolean = true
}

private fun EPackage.hasClassifierNamed(name: String): Boolean {
    return this.eClassifiers.any { it.name == name }
}

private fun EPackage.classifierByName(name: String): EClassifier {
    return this.eClassifiers.find { it.name == name } ?: throw IllegalArgumentException(
        "No classifier named $name was found"
    )
}
