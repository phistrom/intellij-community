/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.test.TestRoot;
import org.junit.runner.RunWith;

/*
 * This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}.
 * DO NOT MODIFY MANUALLY.
 */
@TestRoot("idea")
@SuppressWarnings("all")
@TestMetadata("testData/expressionSelection")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public class ExpressionSelectionTestGenerated extends AbstractExpressionSelectionTest {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTestExpressionSelection, this, testDataFilePath);
    }

    @TestMetadata("binaryExpr.kt")
    public void testBinaryExpr() throws Exception {
        runTest("testData/expressionSelection/binaryExpr.kt");
    }

    @TestMetadata("labelledStatement.kt")
    public void testLabelledStatement() throws Exception {
        runTest("testData/expressionSelection/labelledStatement.kt");
    }

    @TestMetadata("labelledThis.kt")
    public void testLabelledThis() throws Exception {
        runTest("testData/expressionSelection/labelledThis.kt");
    }

    @TestMetadata("noExpression.kt")
    public void testNoExpression() throws Exception {
        runTest("testData/expressionSelection/noExpression.kt");
    }
}
