// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.webSymbols.webTypes.filters

import com.intellij.webSymbols.*
import com.intellij.webSymbols.completion.WebSymbolCodeCompletionItem
import com.intellij.webSymbols.registry.WebSymbolsRegistry

class WebSymbolsMatchPrefixFilter : WebSymbolsFilter {

  override fun filterCodeCompletions(codeCompletions: List<WebSymbolCodeCompletionItem>,
                                     registry: WebSymbolsRegistry,
                                     context: List<WebSymbolsContainer>,
                                     properties: Map<String, Any>): List<WebSymbolCodeCompletionItem> {
    val prefix = properties["prefix"] as? String ?: return codeCompletions
    return codeCompletions.filter { it.name.startsWith(prefix) }
  }

  override fun filterNameMatches(matches: List<WebSymbol>,
                                 registry: WebSymbolsRegistry,
                                 context: List<WebSymbolsContainer>,
                                 properties: Map<String, Any>): List<WebSymbol> {
    val prefix = properties["prefix"] as? String ?: return matches
    return matches.filter { it.name.startsWith(prefix) }
  }

}