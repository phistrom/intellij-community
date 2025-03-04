// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.refactoring.safeDelete

import com.intellij.ide.IdeBundle
import com.intellij.java.refactoring.JavaRefactoringBundle
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.refactoring.util.RefactoringDescriptionLocation
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.toPsiParameters
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import org.jetbrains.kotlin.utils.ifEmpty
import java.util.*

fun getParametersToSearch(element: KtParameter): List<PsiElement> {
        return element.toPsiParameters().flatMap { psiParameter ->
            checkParametersInMethodHierarchy(psiParameter) ?: emptyList()
        }.ifEmpty { listOf(element) }
    }

private fun checkParametersInMethodHierarchy(parameter: PsiParameter): Collection<PsiElement>? {
    val method = parameter.declarationScope as PsiMethod

    val parametersToDelete = ProgressManager.getInstance()
        .runProcessWithProgressSynchronously(ThrowableComputable<Collection<PsiElement>?, RuntimeException> {
            runReadAction { collectParametersHierarchy(method, parameter) }
        }, JavaRefactoringBundle.message("progress.title.collect.hierarchy", parameter.name), true, parameter.project)
    if (parametersToDelete == null || parametersToDelete.size <= 1 || isUnitTestMode()) return parametersToDelete

    val message = JavaRefactoringBundle.message(
        "0.is.a.part.of.method.hierarchy.do.you.want.to.delete.multiple.parameters",
        ElementDescriptionUtil.getElementDescription(method, RefactoringDescriptionLocation.WITHOUT_PARENT)
    )
    val exitCode =
        Messages.showOkCancelDialog(parameter.project, message, IdeBundle.message("title.warning"), Messages.getQuestionIcon())
    return if (exitCode == Messages.OK) parametersToDelete else null
}

private fun collectParametersHierarchy(method: PsiMethod, parameter: PsiParameter): Set<PsiElement> {
    val queue = ArrayDeque<PsiMethod>()
    val visited = HashSet<PsiMethod>()
    val parametersToDelete = HashSet<PsiElement>()

    queue.add(method)
    while (!queue.isEmpty()) {
        val currentMethod = queue.poll()

        visited += currentMethod
        addParameter(currentMethod, parametersToDelete, parameter)

        currentMethod.findSuperMethods(true)
            .filter { it !in visited }
            .forEach { queue.offer(it) }
        OverridingMethodsSearch.search(currentMethod)
            .filter { it !in visited }
            .forEach { queue.offer(it) }
    }
    return parametersToDelete
}

private fun addParameter(method: PsiMethod, result: MutableSet<PsiElement>, parameter: PsiParameter) {
    val parameterIndex = parameter.unwrapped!!.parameterIndex()

    if (method is KtLightMethod) {
        val declaration = method.kotlinOrigin
        if (declaration is KtFunction) {
            result.add(declaration.valueParameters[parameterIndex])
        }
    } else {
        result.add(method.parameterList.parameters[parameterIndex])
    }
}