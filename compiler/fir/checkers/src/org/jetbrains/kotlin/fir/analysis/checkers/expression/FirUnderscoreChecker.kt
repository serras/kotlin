/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.checkUnderscoreDiagnostics
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.isUnderscore
import org.jetbrains.kotlin.fir.diagnostics.ConeUnderscoreUsageWithoutBackticks
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.toResolvedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

object FirUnderscoreChecker : FirBasicExpressionChecker() {
    override fun check(expression: FirStatement, context: CheckerContext, reporter: DiagnosticReporter) {
        when (expression) {
            is FirResolvable -> {
                if (diagnosticsCheckNeeded(expression)) {
                    checkUnderscoreDiagnostics(expression.calleeReference.source, context, reporter, true)
                }
            }
            is FirResolvedQualifier -> {
                for (reservedUnderscoreDiagnostic in expression.nonFatalDiagnostics) {
                    if (reservedUnderscoreDiagnostic is ConeUnderscoreUsageWithoutBackticks) {
                        reporter.reportOn(reservedUnderscoreDiagnostic.source, FirErrors.UNDERSCORE_USAGE_WITHOUT_BACKTICKS, context)
                    }
                }
            }
        }
    }

    private fun diagnosticsCheckNeeded(expression: FirResolvable): Boolean {
        return when (expression) {
            is FirImplicitInvokeCall -> expression.calleeReference.name.asString().isUnderscore
            is FirCall -> expression.calleeReference.toResolvedSymbol<FirFunctionSymbol<*>>()?.callableId?.callableName?.asString()?.isUnderscore == true
            else -> true
        }
    }
}
