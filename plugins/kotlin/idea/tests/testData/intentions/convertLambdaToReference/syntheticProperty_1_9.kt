// WITH_STDLIB
// COMPILER_ARGUMENTS: -XXLanguage:+ReferencesToSyntheticJavaProperties
// AFTER-WARNING: References to the synthetic extension properties for a Java get/set methods aren't supported fully, please use reference to a method
val x = listOf(Int::class.java).map { <caret>it.name }
