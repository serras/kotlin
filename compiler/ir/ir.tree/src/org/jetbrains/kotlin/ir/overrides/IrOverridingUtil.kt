/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.overrides

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.linkage.partial.IrUnimplementedOverridesStrategy
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.collectAndFilterRealOverrides
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.resolve.OverridingUtil.OverrideCompatibilityInfo
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.TypeCheckerState
import org.jetbrains.kotlin.utils.filterIsInstanceAnd
import org.jetbrains.kotlin.utils.memoryOptimizedMap
import org.jetbrains.kotlin.utils.memoryOptimizedMapNotNull

// TODO:
// The below pile of code is basically half of OverridingUtil.java
// adapted to IR and converted to Kotlin.
// Need to convert frontend's OverridingUtil to Kotlin and merge this codes
// to use abstract overridable member interfaces.

class IrOverridingUtil(
    private val typeSystem: IrTypeSystemContext,
    private val fakeOverrideBuilder: FakeOverrideBuilderStrategy,
    private val externalOverridabilityConditions: List<IrExternalOverridabilityCondition>,
) {
    private val overrideChecker = IrOverrideChecker(typeSystem, externalOverridabilityConditions)
    private val originals = mutableMapOf<IrOverridableMember, IrOverridableMember>()
    private val IrOverridableMember.original get() = originals[this] ?: error("No original for ${this.render()}")
    private val originalSuperTypes = mutableMapOf<IrOverridableMember, IrType>()

    fun clear() {
        originals.clear()
        originalSuperTypes.clear()
    }

    private var IrOverridableMember.overriddenSymbols: List<IrSymbol>
        get() = when (this) {
            is IrSimpleFunction -> this.overriddenSymbols
            is IrProperty -> this.overriddenSymbols
            else -> error("Unexpected declaration for overriddenSymbols: $this")
        }
        set(value) {
            when (this) {
                is IrSimpleFunction -> this.overriddenSymbols =
                    value.memoryOptimizedMap { it as? IrSimpleFunctionSymbol ?: error("Unexpected function overridden symbol: $it") }
                is IrProperty -> {
                    val overriddenProperties = value.memoryOptimizedMap { it as? IrPropertySymbol ?: error("Unexpected property overridden symbol: $it") }
                    val getter = this.getter ?: error("Property has no getter: ${render()}")
                    getter.overriddenSymbols = overriddenProperties.memoryOptimizedMap { it.owner.getter!!.symbol }
                    this.setter?.let { setter ->
                        setter.overriddenSymbols = overriddenProperties.memoryOptimizedMapNotNull { it.owner.setter?.symbol }
                    }
                    this.overriddenSymbols = overriddenProperties
                }
                else -> error("Unexpected declaration for overriddenSymbols: $this")
            }
        }

    fun buildFakeOverridesForClass(clazz: IrClass, oldSignatures: Boolean) {
        val superTypes = clazz.superTypes

        val fromCurrent = clazz.declarations.filterIsInstance<IrOverridableMember>()

        val allFromSuper = superTypes.flatMap { superType ->
            val superClass = superType.getClass() ?: error("Unexpected super type: $superType")
            superClass.declarations
                .filter { it.isOverridableMemberOrAccessor() }
                .map {
                    val overriddenMember = it as IrOverridableMember
                    val fakeOverride = fakeOverrideBuilder.fakeOverrideMember(superType, overriddenMember, clazz)
                    originals[fakeOverride] = overriddenMember
                    originalSuperTypes[fakeOverride] = superType
                    fakeOverride
                }
        }

        val allFromSuperByName = allFromSuper.groupBy { it.name }

        allFromSuperByName.forEach { group ->
            generateOverridesInFunctionGroup(
                group.value,
                fromCurrent.filter { it.name == group.key && !it.isStaticMember },
                clazz, oldSignatures
            )
        }
    }

    fun buildFakeOverridesForClassUsingOverriddenSymbols(
        clazz: IrClass,
        implementedMembers: List<IrOverridableMember> = emptyList(),
        compatibilityMode: Boolean,
        ignoredParentSymbols: List<IrSymbol> = emptyList()
    ): List<IrOverridableMember> {
        val overriddenMembers = (clazz.declarations.filterIsInstance<IrOverridableMember>() + implementedMembers)
            .flatMap { member -> member.overriddenSymbols.map { it.owner } }
            .toSet()

        val unoverriddenSuperMembers = clazz.superTypes.flatMap { superType ->
            val superClass = superType.getClass() ?: error("Unexpected super type: $superType")
            superClass.declarations
                .filterIsInstanceAnd<IrOverridableMember> {
                    it !in overriddenMembers && it.symbol !in ignoredParentSymbols && !it.isStaticMember && !DescriptorVisibilities.isPrivate(it.visibility)
                }
                .map { overriddenMember ->
                    val fakeOverride = fakeOverrideBuilder.fakeOverrideMember(superType, overriddenMember, clazz)
                    originals[fakeOverride] = overriddenMember
                    originalSuperTypes[fakeOverride] = superType
                    fakeOverride
                }
        }

        val unoverriddenSuperMembersGroupedByName = unoverriddenSuperMembers.groupBy { it.name }
        val fakeOverrides = mutableListOf<IrOverridableMember>()
        for (group in unoverriddenSuperMembersGroupedByName.values) {
            createAndBindFakeOverrides(clazz, group, fakeOverrides, compatibilityMode)
        }
        return fakeOverrides
    }

    private val IrOverridableMember.isStaticMember: Boolean
        get() = when (this) {
            is IrFunction ->
                dispatchReceiverParameter == null
            is IrProperty ->
                backingField?.isStatic == true ||
                        getter?.let { it.dispatchReceiverParameter == null } == true
            else -> error("Unknown overridable member: ${render()}")
        }

    private fun generateOverridesInFunctionGroup(
        membersFromSupertypes: List<IrOverridableMember>,
        membersFromCurrent: List<IrOverridableMember>,
        current: IrClass,
        compatibilityMode: Boolean
    ) {
        val notOverridden = membersFromSupertypes.toMutableSet()

        for (fromCurrent in membersFromCurrent) {
            val bound = extractAndBindOverridesForMember(fromCurrent, membersFromSupertypes)
            notOverridden -= bound
        }

        val addedFakeOverrides = mutableListOf<IrOverridableMember>()
        createAndBindFakeOverrides(current, notOverridden, addedFakeOverrides, compatibilityMode)
        current.declarations.addAll(addedFakeOverrides)
    }

    private fun extractAndBindOverridesForMember(
        fromCurrent: IrOverridableMember,
        descriptorsFromSuper: Collection<IrOverridableMember>
    ): Collection<IrOverridableMember> {
        val bound = ArrayList<IrOverridableMember>(descriptorsFromSuper.size)
        val overridden = mutableSetOf<IrOverridableMember>()

        for (fromSupertype in descriptorsFromSuper) {
            // Note: We do allow overriding multiple FOs at once one of which is `isInline=true`.
            when (overrideChecker.isOverridableBy(fromSupertype, fromCurrent, checkIsInlineFlag = true).result) {
                OverrideCompatibilityInfo.Result.OVERRIDABLE -> {
                    val isVisibleFake = fromSupertype.visibility != DescriptorVisibilities.INVISIBLE_FAKE
                    if (isVisibleFake && isVisibleForOverride(fromCurrent, fromSupertype.original))
                        overridden += fromSupertype
                    bound += fromSupertype
                }
                OverrideCompatibilityInfo.Result.CONFLICT -> {
                    bound += fromSupertype
                }
                OverrideCompatibilityInfo.Result.INCOMPATIBLE -> Unit
            }
        }

        fromCurrent.overriddenSymbols = overridden.memoryOptimizedMap { it.original.symbol }

        return bound
    }

    private fun createAndBindFakeOverrides(
        current: IrClass,
        notOverridden: Collection<IrOverridableMember>,
        addedFakeOverrides: MutableList<IrOverridableMember>,
        compatibilityMode: Boolean
    ) {
        val fromSuper = notOverridden.toMutableSet()
        while (fromSuper.isNotEmpty()) {
            val notOverriddenFromSuper: IrOverridableMember = findMemberWithMaxVisibility(filterOutCustomizedFakeOverrides(fromSuper))
            val overridables: Collection<IrOverridableMember> = extractMembersOverridableInBothWays(
                notOverriddenFromSuper,
                fromSuper
            )
            createAndBindFakeOverride(overridables, current, addedFakeOverrides, compatibilityMode)
        }
    }

    /**
     * If there is a mix of [IrOverridableMember]s with origin=[IrDeclarationOrigin.FAKE_OVERRIDE]s (true "fake overrides")
     * and [IrOverridableMember]s that were customized with the help of [IrUnimplementedOverridesStrategy] (customized "fake overrides"),
     * then leave only true ones. Rationale: They should point to non-abstract callable members in one of super classes, so
     * effectively they are implemented in the current class.
     */
    private fun filterOutCustomizedFakeOverrides(overridableMembers: Collection<IrOverridableMember>): Collection<IrOverridableMember> {
        if (overridableMembers.size < 2) return overridableMembers

        val (trueFakeOverrides, customizedFakeOverrides) = overridableMembers.partition { it.origin == IrDeclarationOrigin.FAKE_OVERRIDE }
        return trueFakeOverrides.ifEmpty { customizedFakeOverrides }
    }

    private fun determineModalityForFakeOverride(
        members: Collection<IrOverridableMember>,
        current: IrClass
    ): Modality {
        // Optimization: avoid creating hash sets in frequent cases when modality can be computed trivially
        var hasOpen = false
        var hasAbstract = false
        for (member in members) {
            when (member.modality) {
                Modality.FINAL -> return Modality.FINAL
                Modality.SEALED -> throw IllegalStateException("Member cannot have SEALED modality: $member")
                Modality.OPEN -> hasOpen = true
                Modality.ABSTRACT -> hasAbstract = true
            }
        }

        // Fake overrides of abstract members in non-abstract expected classes should not be abstract, because otherwise it would be
        // impossible to inherit a non-expected class from that expected class in common code.
        // We're making their modality that of the containing class, because this is the least confusing behavior for the users.
        // However, it may cause problems if we reuse resolution results of common code when compiling platform code (see KT-15220)
        val transformAbstractToClassModality =
            current.isExpect && current.modality !== Modality.ABSTRACT && current.modality !== Modality.SEALED
        if (hasOpen && !hasAbstract) {
            return Modality.OPEN
        }
        if (!hasOpen && hasAbstract) {
            return if (transformAbstractToClassModality) current.modality else Modality.ABSTRACT
        }

        val realOverrides = members
            .map { originals[it]!! }
            .collectAndFilterRealOverrides()
        return getMinimalModality(realOverrides, transformAbstractToClassModality, current.modality)
    }

    private fun areEquivalent(a: IrOverridableMember, b: IrOverridableMember) = (a == b)

    fun overrides(f: IrOverridableMember, g: IrOverridableMember): Boolean {

        if (f != g && areEquivalent(f.original, g.original)) return true

        for (overriddenFunction in getOverriddenDeclarations(f)) {
            if (areEquivalent(g.original, overriddenFunction)) return true
        }
        return false
    }

    private fun getOverriddenDeclarations(member: IrOverridableMember): Set<IrOverridableMember> {
        val result = mutableSetOf<IrOverridableMember>()
        collectOverriddenDeclarations(member.original, result)
        return result
    }

    private fun collectOverriddenDeclarations(
        member: IrOverridableMember,
        result: MutableSet<IrOverridableMember>
    ) {
        if (member.isReal) {
            result.add(member)
        } else {
            check(member.overriddenSymbols.isNotEmpty()) { "No overridden descriptors found for (fake override) $member" }
            for (overridden in member.original.overriddenSymbols.map { it.owner as IrOverridableMember }) {
                val original = overridden.original
                collectOverriddenDeclarations(original, result)
                result.add(original)
            }
        }
    }

    private fun getMinimalModality(
        descriptors: Collection<IrOverridableMember>,
        transformAbstractToClassModality: Boolean,
        classModality: Modality
    ): Modality {
        var result = Modality.ABSTRACT
        for (descriptor in descriptors) {
            val effectiveModality =
                if (transformAbstractToClassModality && descriptor.modality === Modality.ABSTRACT) classModality else descriptor.modality
            if (effectiveModality < result) {
                result = effectiveModality
            }
        }
        return result
    }

    private fun IrSimpleFunction.updateAccessorModalityAndVisibility(
        newModality: Modality,
        newVisibility: DescriptorVisibility
    ): IrSimpleFunction? {
        require(this is IrFunctionWithLateBinding) {
            "Unexpected fake override accessor kind: $this"
        }
        // For descriptors it gets INVISIBLE_FAKE.
        if (this.visibility == DescriptorVisibilities.PRIVATE) return null

        this.visibility = newVisibility
        this.modality = newModality
        return this
    }

    private fun createAndBindFakeOverride(
        overridables: Collection<IrOverridableMember>,
        currentClass: IrClass,
        addedFakeOverrides: MutableList<IrOverridableMember>,
        compatibilityMode: Boolean
    ) {
        val effectiveOverridden = overridables.filter { it.isVisibleInClass(currentClass) }

        // The descriptor based algorithm goes further building invisible fakes here,
        // but we don't use invisible fakes in IR
        if (effectiveOverridden.isEmpty()) return

        val modality = determineModalityForFakeOverride(effectiveOverridden, currentClass)
        val visibility = findMemberWithMaxVisibility(effectiveOverridden).visibility
        val mostSpecific = selectMostSpecificMember(effectiveOverridden)

        val fakeOverride = mostSpecific.apply {
            when (this) {
                is IrPropertyWithLateBinding -> {
                    this.visibility = visibility
                    this.modality = modality
                    this.getter = this.getter?.updateAccessorModalityAndVisibility(modality, visibility)
                    this.setter = this.setter?.updateAccessorModalityAndVisibility(modality, visibility)
                }
                is IrFunctionWithLateBinding -> {
                    this.visibility = visibility
                    this.modality = modality
                }
                else -> error("Unexpected fake override kind: $this")
            }
        }

        fakeOverride.overriddenSymbols = effectiveOverridden.memoryOptimizedMap { it.original.symbol }

        require(
            fakeOverride.overriddenSymbols.isNotEmpty()
        ) { "Overridden symbols should be set for " + CallableMemberDescriptor.Kind.FAKE_OVERRIDE }

        addedFakeOverrides.add(fakeOverride)
        fakeOverrideBuilder.linkFakeOverride(fakeOverride, compatibilityMode)
        fakeOverrideBuilder.postProcessGeneratedFakeOverride(fakeOverride, currentClass)
    }

    private fun isVisibilityMoreSpecific(
        a: IrOverridableMember,
        b: IrOverridableMember
    ): Boolean {
        val result =
            DescriptorVisibilities.compare(a.visibility, b.visibility)
        return result == null || result >= 0
    }

    private fun isAccessorMoreSpecific(
        a: IrSimpleFunction?,
        b: IrSimpleFunction?
    ): Boolean {
        return if (a == null || b == null) true else isVisibilityMoreSpecific(a, b)
    }

    private fun TypeCheckerState.isSubtypeOf(a: IrType, b: IrType) =
        AbstractTypeChecker.isSubtypeOf(this, a, b)

    private fun TypeCheckerState.equalTypes(a: IrType, b: IrType) =
        AbstractTypeChecker.equalTypes(this, a, b)

    private fun createTypeCheckerState(a: List<IrTypeParameter>, b: List<IrTypeParameter>): TypeCheckerState =
        createIrTypeCheckerState(IrTypeSystemContextWithAdditionalAxioms(typeSystem, a, b))

    private fun isReturnTypeMoreSpecific(
        a: IrOverridableMember,
        aReturnType: IrType,
        b: IrOverridableMember,
        bReturnType: IrType
    ): Boolean {
        val typeCheckerState = createTypeCheckerState(a.typeParameters, b.typeParameters)
        return typeCheckerState.isSubtypeOf(aReturnType, bReturnType)
    }

    private fun isMoreSpecific(
        a: IrOverridableMember,
        b: IrOverridableMember
    ): Boolean {
        val aReturnType = a.returnType
        val bReturnType = b.returnType
        if (!isVisibilityMoreSpecific(a, b)) return false
        if (a is IrSimpleFunction) {
            require(b is IrSimpleFunction) { "b is " + b.javaClass }
            return isReturnTypeMoreSpecific(a, aReturnType, b, bReturnType)
        }
        if (a is IrProperty) {
            require(b is IrProperty) { "b is " + b.javaClass }
            if (!isAccessorMoreSpecific(
                    a.setter,
                    b.setter
                )
            ) return false
            return if (a.isVar && b.isVar) {
                createTypeCheckerState(
                    a.getter!!.typeParameters,
                    b.getter!!.typeParameters
                ).equalTypes(aReturnType, bReturnType)
            } else {
                // both vals or var vs val: val can't be more specific then var
                !(!a.isVar && b.isVar) && isReturnTypeMoreSpecific(
                    a, aReturnType,
                    b, bReturnType
                )
            }
        }
        error("Unexpected callable: $a")
    }

    private fun isMoreSpecificThenAllOf(
        candidate: IrOverridableMember,
        descriptors: Collection<IrOverridableMember>
    ): Boolean {
        // NB subtyping relation in Kotlin is not transitive in presence of flexible types:
        //  String? <: String! <: String, but not String? <: String
        for (descriptor in descriptors) {
            if (!isMoreSpecific(candidate, descriptor)) {
                return false
            }
        }
        return true
    }

    private fun selectMostSpecificMember(
        overridables: Collection<IrOverridableMember>
    ): IrOverridableMember {
        require(!overridables.isEmpty()) { "Should have at least one overridable descriptor" }
        if (overridables.size == 1) {
            return overridables.first()
        }
        val candidates = mutableListOf<IrOverridableMember>()
        var transitivelyMostSpecific = overridables.first()
        val transitivelyMostSpecificDescriptor = transitivelyMostSpecific
        for (overridable in overridables) {
            if (isMoreSpecificThenAllOf(overridable, overridables)
            ) {
                candidates.add(overridable)
            }
            if (isMoreSpecific(
                    overridable,
                    transitivelyMostSpecificDescriptor
                )
                && !isMoreSpecific(
                    transitivelyMostSpecificDescriptor,
                    overridable
                )
            ) {
                transitivelyMostSpecific = overridable
            }
        }
        if (candidates.isEmpty()) {
            return transitivelyMostSpecific
        } else if (candidates.size == 1) {
            return candidates.first()
        }
        var firstNonFlexible: IrOverridableMember? = null
        for (candidate in candidates) {
            if (candidate.returnType !is IrDynamicType) {
                firstNonFlexible = candidate
                break
            }
        }
        return firstNonFlexible ?: candidates.first()
    }

    private fun extractMembersOverridableInBothWays(
        overrider: IrOverridableMember,
        extractFrom: MutableCollection<IrOverridableMember>
    ): Collection<IrOverridableMember> {
        val overridable = arrayListOf<IrOverridableMember>()
        overridable.add(overrider)
        val iterator = extractFrom.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (overrider === candidate) {
                iterator.remove()
                continue
            }
            val finalResult = overrideChecker.getBothWaysOverridability(overrider, candidate)
            if (finalResult == OverrideCompatibilityInfo.Result.OVERRIDABLE) {
                overridable.add(candidate)
                iterator.remove()
            } else if (finalResult == OverrideCompatibilityInfo.Result.CONFLICT) {
                iterator.remove()
            }
        }
        return overridable
    }
}

private val IrOverridableMember.typeParameters: List<IrTypeParameter>
    get() = when (this) {
        is IrSimpleFunction -> typeParameters
        is IrProperty -> getter?.typeParameters.orEmpty()
        else -> error("Unexpected type of declaration: ${this::class.java}, $this")
    }

private val IrOverridableMember.returnType: IrType
    get() = when (this) {
        is IrSimpleFunction -> returnType
        is IrProperty -> getter!!.returnType
        else -> error("Unexpected type of declaration: ${this::class.java}, $this")
    }

fun IrSimpleFunction.isOverridableFunction(): Boolean =
    visibility != DescriptorVisibilities.PRIVATE && hasDispatchReceiver

fun IrProperty.isOverridableProperty(): Boolean =
    visibility != DescriptorVisibilities.PRIVATE && (getter.hasDispatchReceiver || setter.hasDispatchReceiver)

fun IrDeclaration.isOverridableMemberOrAccessor(): Boolean = when (this) {
    is IrSimpleFunction -> isOverridableFunction()
    is IrProperty -> isOverridableProperty()
    else -> false
}
