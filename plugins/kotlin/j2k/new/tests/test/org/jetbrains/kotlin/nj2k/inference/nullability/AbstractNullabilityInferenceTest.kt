// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.nj2k.inference.nullability

import com.intellij.openapi.application.runWriteAction
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import com.intellij.util.ThrowableRunnable
import org.jetbrains.kotlin.idea.j2k.post.processing.inference.common.*
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.test.runAll
import org.jetbrains.kotlin.nj2k.descriptorByFileDirective
import org.jetbrains.kotlin.nj2k.inference.AbstractConstraintCollectorTest
import org.jetbrains.kotlin.idea.j2k.post.processing.inference.common.collectors.CallExpressionConstraintCollector
import org.jetbrains.kotlin.idea.j2k.post.processing.inference.common.collectors.CommonConstraintsCollector
import org.jetbrains.kotlin.idea.j2k.post.processing.inference.common.collectors.FunctionConstraintsCollector
import org.jetbrains.kotlin.idea.j2k.post.processing.inference.nullability.*
import org.jetbrains.kotlin.psi.KtConstructorCalleeExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import java.io.File

abstract class AbstractNullabilityInferenceTest : AbstractConstraintCollectorTest() {
    override fun createInferenceFacade(resolutionFacade: ResolutionFacade): InferenceFacade {
        val typeEnhancer = NullabilityBoundTypeEnhancer(resolutionFacade)
        return InferenceFacade(
          object : ContextCollector(resolutionFacade) {
                override fun ClassReference.getState(typeElement: KtTypeElement?): State =
                    State.UNKNOWN
            },
          ConstraintsCollectorAggregator(
            resolutionFacade,
            NullabilityConstraintBoundProvider(),
            listOf(
                    CommonConstraintsCollector(),
                    CallExpressionConstraintCollector(),
                    FunctionConstraintsCollector(ResolveSuperFunctionsProvider(resolutionFacade)),
                    NullabilityConstraintsCollector()
                )
            ),
          BoundTypeCalculatorImpl(resolutionFacade, typeEnhancer),
          NullabilityStateUpdater(),
          NullabilityDefaultStateProvider(),
          renderDebugTypes = true
        )
    }

    override fun KtFile.prepareFile() = runWriteAction {
        fun KtTypeReference.updateNullability() {
            NullabilityStateUpdater.changeState(typeElement ?: return, toNullable = true)
            for (typeArgument in typeElement!!.typeArgumentsAsTypes) {
                typeArgument.updateNullability()
            }
        }
        for (typeReference in collectDescendantsOfType<KtTypeReference>()) {
            if (typeReference.parent is KtConstructorCalleeExpression) continue
            typeReference.updateNullability()
        }
        deleteComments()
    }

    override fun setUp() {
        super.setUp()
        JavaCodeStyleSettings.getInstance(project).USE_EXTERNAL_ANNOTATIONS = true
    }

    override fun tearDown() {
        runAll(
            ThrowableRunnable { JavaCodeStyleSettings.getInstance(project).USE_EXTERNAL_ANNOTATIONS = false },
            ThrowableRunnable { super.tearDown() }
        )
    }

    override fun getProjectDescriptor() = descriptorByFileDirective(File(testDataPath, fileName()))
}