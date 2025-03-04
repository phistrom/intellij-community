// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.webSymbols

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

open class DebugOutputPrinter {

  fun printValue(value: Any): String =
    StringBuilder().printValue(0, value).toString()

  // KT-11488 - cannot call super when overriding StringBuilder.printValue
  protected fun StringBuilder.printValue(level: Int, value: Any?): StringBuilder =
    printValueImpl(this, level, value)

  protected open fun printValueImpl(builder: StringBuilder, level: Int, value: Any?): StringBuilder =
    when (value) {
      is String -> builder.append("\"").append(value.ellipsis(80)).append("\"")
      is PsiElement -> builder.printPsiElement(value)
      is List<*> -> builder.printList(level, value)
      is Map<*, *> -> builder.printMap(level, value)
      null -> builder.append("<null>")
      else -> builder.append(value)
    }

  protected fun StringBuilder.printProperty(level: Int, name: String, value: Any?): StringBuilder {
    if (value == null) return this
    indent(level).append(name).append(": ")
      .printValue(level, value)
      .append(",\n")
    return this
  }

  protected fun StringBuilder.printMap(level: Int,
                                       map: Map<*, *>): StringBuilder =
    printObject(level) {
      for (entry in map) {
        printProperty(it, entry.key.toString(), entry.value)
      }
    }

  protected fun StringBuilder.printList(level: Int, list: List<*>): StringBuilder {
    append("[")
    if (list.isEmpty()) {
      append("],\n")
    }
    else {
      append('\n')
      list.forEach {
        indent(level + 1).printValue(level + 1, it).append(",\n")
      }
      indent(level).append("]")
    }
    return this
  }

  protected fun StringBuilder.printObject(level: Int,
                                          printer: (level: Int) -> Unit): StringBuilder {
    append("{\n")
    printer(level + 1)
    indent(level).append("}")
    return this
  }

  protected fun StringBuilder.indent(level: Int): StringBuilder =
    append(" ".repeat(level))

  protected fun StringBuilder.printPsiElement(element: PsiElement): StringBuilder {
    append(element::class.java.simpleName)
      .append(" <")
      .append(element.containingFile.virtualFile?.path)
    if (element !is PsiFile) append(": " + element.textRange)
    return append(">")
  }

  protected fun String.ellipsis(maxLength: Int): String =
    substring(0, length.coerceAtMost(maxLength)) + if (length > maxLength) "…" else ""

}