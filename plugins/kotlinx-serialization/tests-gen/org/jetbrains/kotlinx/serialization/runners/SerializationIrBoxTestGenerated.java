/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.runners;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TargetBackend;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlinx.serialization.TestGeneratorKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/kotlinx-serialization/testData/boxIr")
@TestDataPath("$PROJECT_ROOT")
public class SerializationIrBoxTestGenerated extends AbstractSerializationIrBoxTest {
    @Test
    public void testAllFilesPresentInBoxIr() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/kotlinx-serialization/testData/boxIr"), Pattern.compile("^(.+)\\.kt$"), null, TargetBackend.JVM_IR, true);
    }

    @Test
    @TestMetadata("annotationsOnFile.kt")
    public void testAnnotationsOnFile() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/annotationsOnFile.kt");
    }

    @Test
    @TestMetadata("classSerializerAsObject.kt")
    public void testClassSerializerAsObject() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/classSerializerAsObject.kt");
    }

    @Test
    @TestMetadata("contextualByDefault.kt")
    public void testContextualByDefault() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/contextualByDefault.kt");
    }

    @Test
    @TestMetadata("enumsAreCached.kt")
    public void testEnumsAreCached() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/enumsAreCached.kt");
    }

    @Test
    @TestMetadata("genericBaseClassMultiple.kt")
    public void testGenericBaseClassMultiple() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/genericBaseClassMultiple.kt");
    }

    @Test
    @TestMetadata("genericBaseClassSimple.kt")
    public void testGenericBaseClassSimple() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/genericBaseClassSimple.kt");
    }

    @Test
    @TestMetadata("generics.kt")
    public void testGenerics() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/generics.kt");
    }

    @Test
    @TestMetadata("inlineClasses.kt")
    public void testInlineClasses() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/inlineClasses.kt");
    }

    @Test
    @TestMetadata("intrinsicsBox.kt")
    public void testIntrinsicsBox() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/intrinsicsBox.kt");
    }

    @Test
    @TestMetadata("intrinsicsStarProjections.kt")
    public void testIntrinsicsStarProjections() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/intrinsicsStarProjections.kt");
    }

    @Test
    @TestMetadata("metaSerializable.kt")
    public void testMetaSerializable() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/metaSerializable.kt");
    }

    @Test
    @TestMetadata("multimoduleInheritance.kt")
    public void testMultimoduleInheritance() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/multimoduleInheritance.kt");
    }

    @Test
    @TestMetadata("multipleGenericsPolymorphic.kt")
    public void testMultipleGenericsPolymorphic() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/multipleGenericsPolymorphic.kt");
    }

    @Test
    @TestMetadata("sealedInterfaces.kt")
    public void testSealedInterfaces() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/sealedInterfaces.kt");
    }

    @Test
    @TestMetadata("serialInfo.kt")
    public void testSerialInfo() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/serialInfo.kt");
    }

    @Test
    @TestMetadata("serializableOnPropertyType.kt")
    public void testSerializableOnPropertyType() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/serializableOnPropertyType.kt");
    }

    @Test
    @TestMetadata("starProjections.kt")
    public void testStarProjections() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/starProjections.kt");
    }

    @Test
    @TestMetadata("typealiasesTest.kt")
    public void testTypealiasesTest() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/typealiasesTest.kt");
    }

    @Test
    @TestMetadata("useSerializersChain.kt")
    public void testUseSerializersChain() throws Exception {
        runTest("plugins/kotlinx-serialization/testData/boxIr/useSerializersChain.kt");
    }
}
