package com.raizlabs.dbflow5.processor.definition.column

import com.grosner.kpoet.S
import com.grosner.kpoet.`return`
import com.grosner.kpoet.case
import com.raizlabs.dbflow5.annotation.ColumnMap
import com.raizlabs.dbflow5.annotation.ConflictAction
import com.raizlabs.dbflow5.annotation.ForeignKey
import com.raizlabs.dbflow5.annotation.ForeignKeyAction
import com.raizlabs.dbflow5.annotation.ForeignKeyReference
import com.raizlabs.dbflow5.annotation.QueryModel
import com.raizlabs.dbflow5.annotation.Table
import com.raizlabs.dbflow5.processor.ClassNames
import com.raizlabs.dbflow5.processor.ProcessorManager
import com.raizlabs.dbflow5.processor.definition.BaseTableDefinition
import com.raizlabs.dbflow5.processor.definition.QueryModelDefinition
import com.raizlabs.dbflow5.processor.definition.TableDefinition
import com.raizlabs.dbflow5.processor.utils.annotation
import com.raizlabs.dbflow5.processor.utils.fromTypeMirror
import com.raizlabs.dbflow5.processor.utils.implementsClass
import com.raizlabs.dbflow5.processor.utils.isNullOrEmpty
import com.raizlabs.dbflow5.processor.utils.isSubclass
import com.raizlabs.dbflow5.processor.utils.toTypeElement
import com.raizlabs.dbflow5.processor.utils.toTypeErasedElement
import com.raizlabs.dbflow5.quote
import com.raizlabs.dbflow5.quoteIfNeeded
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import java.util.concurrent.atomic.AtomicInteger
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.type.MirroredTypeException

/**
 * Description: Represents both a [ForeignKey] and [ColumnMap]. Builds up the model of fields
 * required to generate definitions.
 */
class ReferenceColumnDefinition(manager: ProcessorManager, tableDefinition: BaseTableDefinition,
                                element: Element, isPackagePrivate: Boolean)
    : ColumnDefinition(manager, element, tableDefinition, isPackagePrivate) {

    private val _referenceDefinitionList: MutableList<ReferenceDefinition> = arrayListOf()
    val referenceDefinitionList: List<ReferenceDefinition>
        get() {
            checkNeedsReferences()
            return _referenceDefinitionList
        }

    var referencedClassName: ClassName? = null

    var onDelete = ForeignKeyAction.NO_ACTION
    var onUpdate = ForeignKeyAction.NO_ACTION

    var isStubbedRelationship: Boolean = false

    var isReferencingTableObject: Boolean = false
    var implementsModel = false
    var extendsBaseModel = false

    var references: List<ReferenceSpecificationDefinition>? = null

    var nonModelColumn: Boolean = false

    var saveForeignKeyModel: Boolean = false
    var deleteForeignKeyModel: Boolean = false

    var needsReferences = true

    var deferred = false

    var isColumnMap = false

    override val typeConverterElementNames: List<TypeName?>
        get() = referenceDefinitionList.filter { it.hasTypeConverter }.map { it.columnClassName }

    init {

        element.annotation<ColumnMap>()?.let {
            isColumnMap = true
            // column map is stubbed
            isStubbedRelationship = true
            findReferencedClassName(manager)

            typeElement?.let { typeElement ->
                QueryModelDefinition(typeElement, manager).apply {
                    databaseTypeName = tableDefinition.databaseTypeName
                    manager.addQueryModelDefinition(this)
                }
            }

            references = it.references.map {
                ReferenceSpecificationDefinition(columnName = it.columnName,
                    referenceName = it.columnMapFieldName,
                    onNullConflictAction = it.notNull.onNullConflict,
                    defaultValue = it.defaultValue)
            }
        }

        element.annotation<ForeignKey>()?.let { foreignKey ->
            if (tableDefinition !is TableDefinition) {
                manager.logError("Class $elementName cannot declare a @ForeignKey. Use @ColumnMap instead.")
            }
            onUpdate = foreignKey.onUpdate
            onDelete = foreignKey.onDelete

            deferred = foreignKey.deferred
            isStubbedRelationship = foreignKey.stubbedRelationship

            try {
                foreignKey.tableClass
            } catch (mte: MirroredTypeException) {
                referencedClassName = fromTypeMirror(mte.typeMirror, manager)
            }

            val erasedElement = element.toTypeErasedElement()

            // hopefully intentionally left blank
            if (referencedClassName == TypeName.OBJECT) {
                findReferencedClassName(manager)
            }

            if (referencedClassName == null) {
                manager.logError("Referenced was null for $element within $elementTypeName")
            }

            extendsBaseModel = erasedElement.isSubclass(manager.processingEnvironment, ClassNames.BASE_MODEL)
            implementsModel = erasedElement.implementsClass(manager.processingEnvironment, ClassNames.MODEL)
            isReferencingTableObject = implementsModel || erasedElement.annotation<Table>() != null

            nonModelColumn = !isReferencingTableObject

            saveForeignKeyModel = foreignKey.saveForeignKeyModel
            deleteForeignKeyModel = foreignKey.deleteForeignKeyModel

            references = foreignKey.references.map {
                ReferenceSpecificationDefinition(columnName = it.columnName,
                    referenceName = it.foreignKeyColumnName,
                    onNullConflictAction = it.notNull.onNullConflict,
                    defaultValue = it.defaultValue)
            }
        }

        if (isNotNullType) {
            manager.logError("Foreign Keys must be nullable. Please remove the non-null annotation if using " +
                "Java, or add ? to the type for Kotlin.")
        }
    }

    private fun findReferencedClassName(manager: ProcessorManager) {
        if (elementTypeName is ParameterizedTypeName) {
            val args = (elementTypeName as ParameterizedTypeName).typeArguments
            if (args.size > 0) {
                referencedClassName = ClassName.get(args[0].toTypeElement(manager))
            }
        } else {
            if (referencedClassName == null || referencedClassName == ClassName.OBJECT) {
                referencedClassName = elementTypeName.toTypeElement()?.let { ClassName.get(it) }
            }
        }
    }

    override fun addPropertyDefinition(typeBuilder: TypeSpec.Builder, tableClass: TypeName) {
        referenceDefinitionList.forEach {
            var propParam: TypeName? = null
            val colClassName = it.columnClassName
            colClassName?.let {
                propParam = ParameterizedTypeName.get(ClassNames.PROPERTY, it.box())
            }
            if (it.columnName.isNullOrEmpty()) {
                manager.logError("Found empty reference name at ${it.foreignColumnName}" +
                    " from table ${baseTableDefinition.elementName}")
            }
            typeBuilder.addField(FieldSpec.builder(propParam, it.columnName,
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new \$T(\$T.class, \$S)", propParam, tableClass, it.columnName)
                .addJavadoc(if (isColumnMap) "Column Mapped Field" else ("Foreign Key" + if (isPrimaryKey) " / Primary Key" else "")).build())
        }
    }

    override fun addPropertyCase(methodBuilder: MethodSpec.Builder) {
        referenceDefinitionList.forEach {
            methodBuilder.case(it.columnName.quoteIfNeeded().S) {
                `return`(it.columnName)
            }
        }
    }

    override fun appendIndexInitializer(initializer: CodeBlock.Builder, index: AtomicInteger) {
        if (nonModelColumn) {
            super.appendIndexInitializer(initializer, index)
        } else {
            referenceDefinitionList.forEach {
                if (index.get() > 0) {
                    initializer.add(", ")
                }
                initializer.add(it.columnName)
                index.incrementAndGet()
            }
        }
    }

    override fun addColumnName(codeBuilder: CodeBlock.Builder) {
        for (i in referenceDefinitionList.indices) {
            val reference = referenceDefinitionList[i]
            if (i > 0) {
                codeBuilder.add(",")
            }
            codeBuilder.add(reference.columnName)
        }
    }

    override val updateStatementBlock: CodeBlock
        get() {
            val builder = CodeBlock.builder()
            referenceDefinitionList.indices.forEach { i ->
                if (i > 0) {
                    builder.add(",")
                }
                val referenceDefinition = referenceDefinitionList[i]
                builder.add(CodeBlock.of("${referenceDefinition.columnName.quote()}=?"))
            }
            return builder.build()
        }

    override val insertStatementColumnName: CodeBlock
        get() {
            val builder = CodeBlock.builder()
            referenceDefinitionList.indices.forEach { i ->
                if (i > 0) {
                    builder.add(",")
                }
                val referenceDefinition = referenceDefinitionList[i]
                builder.add(referenceDefinition.columnName.quote())
            }
            return builder.build()
        }

    override val insertStatementValuesString: CodeBlock
        get() {
            val builder = CodeBlock.builder()
            referenceDefinitionList.indices.forEach { i ->
                if (i > 0) {
                    builder.add(",")
                }
                builder.add("?")
            }
            return builder.build()
        }

    override val creationName: CodeBlock
        get() {
            val builder = CodeBlock.builder()
            referenceDefinitionList.indices.forEach { i ->
                if (i > 0) {
                    builder.add(" ,")
                }
                val referenceDefinition = referenceDefinitionList[i]
                builder.add(referenceDefinition.creationStatement)

                if (referenceDefinition.notNull) {
                    builder.add(" NOT NULL ON CONFLICT \$L", referenceDefinition.onNullConflict)
                }
            }
            return builder.build()
        }

    override val primaryKeyName: String
        get() {
            val builder = CodeBlock.builder()
            referenceDefinitionList.indices.forEach { i ->
                if (i > 0) {
                    builder.add(" ,")
                }
                val referenceDefinition = referenceDefinitionList[i]
                builder.add(referenceDefinition.primaryKeyName)
            }
            return builder.build().toString()
        }

    override val contentValuesStatement: CodeBlock
        get() = if (nonModelColumn) {
            super.contentValuesStatement
        } else {
            val codeBuilder = CodeBlock.builder()
            referencedClassName?.let {
                val foreignKeyCombiner = ForeignKeyAccessCombiner(columnAccessor)
                referenceDefinitionList.forEach {
                    foreignKeyCombiner.fieldAccesses += it.contentValuesField
                }
                foreignKeyCombiner.addCode(codeBuilder, AtomicInteger(0))
            }
            codeBuilder.build()
        }

    override fun getSQLiteStatementMethod(index: AtomicInteger, useStart: Boolean,
                                          defineProperty: Boolean): CodeBlock {
        if (nonModelColumn) {
            return super.getSQLiteStatementMethod(index, useStart, defineProperty)
        } else {
            val codeBuilder = CodeBlock.builder()
            referencedClassName?.let {
                val foreignKeyCombiner = ForeignKeyAccessCombiner(columnAccessor)
                referenceDefinitionList.forEach {
                    foreignKeyCombiner.fieldAccesses += it.sqliteStatementField
                }
                foreignKeyCombiner.addCode(codeBuilder, index, useStart, defineProperty)
            }
            return codeBuilder.build()
        }
    }

    override fun getLoadFromCursorMethod(endNonPrimitiveIf: Boolean, index: AtomicInteger, nameAllocator: NameAllocator): CodeBlock {
        if (nonModelColumn) {
            return super.getLoadFromCursorMethod(endNonPrimitiveIf, index, nameAllocator)
        } else {
            val code = CodeBlock.builder()
            referencedClassName?.let { referencedTableClassName ->

                val tableDefinition = manager.getReferenceDefinition(
                    baseTableDefinition.databaseDefinition?.elementTypeName, referencedTableClassName)
                val outputClassName = tableDefinition?.outputClassName
                outputClassName?.let {
                    val foreignKeyCombiner = ForeignKeyLoadFromCursorCombiner(columnAccessor,
                        referencedTableClassName, outputClassName, isStubbedRelationship, nameAllocator)
                    referenceDefinitionList.forEach {
                        foreignKeyCombiner.fieldAccesses += it.partialAccessor
                    }
                    foreignKeyCombiner.addCode(code, index)
                }
            }
            return code.build()
        }
    }

    override fun appendPropertyComparisonAccessStatement(codeBuilder: CodeBlock.Builder) {
        if (nonModelColumn || columnAccessor is TypeConverterScopeColumnAccessor) {
            super.appendPropertyComparisonAccessStatement(codeBuilder)
        } else {

            referencedClassName?.let {
                val foreignKeyCombiner = ForeignKeyAccessCombiner(columnAccessor)
                referenceDefinitionList.forEach {
                    foreignKeyCombiner.fieldAccesses += it.primaryReferenceField
                }
                foreignKeyCombiner.addCode(codeBuilder, AtomicInteger(0))
            }
        }
    }

    fun appendSaveMethod(codeBuilder: CodeBlock.Builder) {
        if (!nonModelColumn && columnAccessor !is TypeConverterScopeColumnAccessor) {
            referencedClassName?.let { referencedTableClassName ->
                val saveAccessor = ForeignKeyAccessField(columnName,
                    SaveModelAccessCombiner(Combiner(columnAccessor, referencedTableClassName, wrapperAccessor,
                        wrapperTypeName, subWrapperAccessor), implementsModel, extendsBaseModel))
                saveAccessor.addCode(codeBuilder, 0, modelBlock)
            }
        }
    }

    fun appendDeleteMethod(codeBuilder: CodeBlock.Builder) {
        if (!nonModelColumn && columnAccessor !is TypeConverterScopeColumnAccessor) {
            referencedClassName?.let { referencedTableClassName ->
                val deleteAccessor = ForeignKeyAccessField(columnName,
                    DeleteModelAccessCombiner(Combiner(columnAccessor, referencedTableClassName, wrapperAccessor,
                        wrapperTypeName, subWrapperAccessor), implementsModel, extendsBaseModel))
                deleteAccessor.addCode(codeBuilder, 0, modelBlock)
            }
        }
    }

    /**
     * If [ForeignKey] has no [ForeignKeyReference]s, we use the primary key the referenced
     * table. We do this post-evaluation so all of the [TableDefinition] can be generated.
     */
    fun checkNeedsReferences() {
        val tableDefinition = baseTableDefinition
        val referencedTableDefinition = manager.getReferenceDefinition(tableDefinition.databaseTypeName, referencedClassName)
        if (referencedTableDefinition == null) {
            manager.logError(ReferenceColumnDefinition::class,
                "Could not find the referenced ${Table::class.java.simpleName} " +
                    "or ${QueryModel::class.java.simpleName} definition $referencedClassName" +
                    " from ${tableDefinition.elementName}. " +
                    "Ensure it exists in the same database as ${tableDefinition.databaseTypeName}")
        } else if (needsReferences) {
            val primaryColumns =
                if (isColumnMap) referencedTableDefinition.columnDefinitions
                else referencedTableDefinition.primaryColumnDefinitions
            if (references?.isEmpty() != false) {
                primaryColumns.forEach {
                    val foreignKeyReferenceDefinition = ReferenceDefinition(manager,
                        elementName, it.elementName, it, this, primaryColumns.size,
                        if (isColumnMap) it.elementName else "",
                        defaultValue = getDefaultValueBlock(it.defaultValue, it.elementTypeName))
                    _referenceDefinitionList.add(foreignKeyReferenceDefinition)
                }
                needsReferences = false
            } else {
                references?.forEach { reference ->
                    val foundDefinition = primaryColumns.find { it.columnName == reference.referenceName }
                    if (foundDefinition == null) {
                        manager.logError(ReferenceColumnDefinition::class,
                            "Could not find referenced column ${reference.referenceName} " +
                                "from reference named ${reference.columnName}")
                    } else {
                        _referenceDefinitionList.add(
                            ReferenceDefinition(manager, elementName,
                                foundDefinition.elementName, foundDefinition, this,
                                primaryColumns.size, reference.columnName,
                                reference.onNullConflictAction,
                                getDefaultValueBlock(reference.defaultValue, foundDefinition.elementTypeName)))
                    }
                }
                needsReferences = false
            }

            if (nonModelColumn && _referenceDefinitionList.size == 1) {
                columnName = _referenceDefinitionList[0].columnName
            }
        }
    }
}

/**
 * Description: defines a ForeignKeyReference or ColumnMapReference.
 */
class ReferenceSpecificationDefinition(val columnName: String,
                                       val referenceName: String,
                                       val onNullConflictAction: ConflictAction,
                                       val defaultValue: String)