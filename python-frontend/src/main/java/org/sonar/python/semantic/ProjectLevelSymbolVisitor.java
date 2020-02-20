/*
 * SonarQube Python Plugin
 * Copyright (C) 2011-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.python.semantic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.plugins.python.api.PythonFile;
import org.sonar.plugins.python.api.symbols.Symbol;
import org.sonar.plugins.python.api.tree.AliasedName;
import org.sonar.plugins.python.api.tree.AnnotatedAssignment;
import org.sonar.plugins.python.api.tree.ArgList;
import org.sonar.plugins.python.api.tree.Argument;
import org.sonar.plugins.python.api.tree.AssignmentStatement;
import org.sonar.plugins.python.api.tree.BaseTreeVisitor;
import org.sonar.plugins.python.api.tree.ClassDef;
import org.sonar.plugins.python.api.tree.DottedName;
import org.sonar.plugins.python.api.tree.Expression;
import org.sonar.plugins.python.api.tree.FileInput;
import org.sonar.plugins.python.api.tree.FunctionDef;
import org.sonar.plugins.python.api.tree.HasSymbol;
import org.sonar.plugins.python.api.tree.ImportFrom;
import org.sonar.plugins.python.api.tree.ImportName;
import org.sonar.plugins.python.api.tree.Name;
import org.sonar.plugins.python.api.tree.RegularArgument;
import org.sonar.plugins.python.api.tree.Token;
import org.sonar.plugins.python.api.tree.Tree;
import org.sonar.plugins.python.api.tree.Tree.Kind;

import static org.sonar.python.semantic.SymbolUtils.assignmentsLhs;

public class ProjectLevelSymbolVisitor {

  private ProjectLevelSymbolVisitor() {
  }

  public static Set<Symbol> globalSymbols(FileInput fileInput, String fullyQualifiedModuleName, PythonFile pythonFile) {
    GlobalSymbolsBindingVisitor globalSymbolsBindingVisitor = new GlobalSymbolsBindingVisitor(fullyQualifiedModuleName, pythonFile);
    fileInput.accept(globalSymbolsBindingVisitor);
    BuiltinSymbols.all().forEach(b -> globalSymbolsBindingVisitor.symbolsByName.putIfAbsent(b, new SymbolImpl(b, b)));
    GlobalSymbolsReadVisitor globalSymbolsReadVisitor = new GlobalSymbolsReadVisitor(globalSymbolsBindingVisitor.symbolsByName, globalSymbolsBindingVisitor.importedSymbols);
    fileInput.accept(globalSymbolsReadVisitor);
    return globalSymbolsReadVisitor.symbolsByName.values().stream().filter(v -> !BuiltinSymbols.all().contains(v.fullyQualifiedName())).collect(Collectors.toSet());
  }

  private static class GlobalSymbolsBindingVisitor extends BaseTreeVisitor {
    private Map<String, Symbol> symbolsByName = new HashMap<>();
    private Map<String, Symbol> importedSymbols = new HashMap<>();
    private String fullyQualifiedModuleName;
    private final PythonFile pythonFile;

    GlobalSymbolsBindingVisitor(String fullyQualifiedModuleName, PythonFile pythonFile) {
      this.fullyQualifiedModuleName = fullyQualifiedModuleName;
      this.pythonFile = pythonFile;
    }

    private Symbol symbol(Tree tree) {
      if (tree.is(Kind.FUNCDEF)) {
        FunctionDef functionDef = (FunctionDef) tree;
        return new FunctionSymbolImpl(functionDef, fullyQualifiedModuleName + "." + functionDef.name().name(), pythonFile);
      } else if (tree.is(Kind.CLASSDEF)) {
        String className = ((ClassDef) tree).name().name();
        return new ClassSymbolImpl(className, fullyQualifiedModuleName + "." + className);
      }
      Name name = (Name) tree;
      return new SymbolImpl(name.name(), fullyQualifiedModuleName + "." + name.name());
    }

    private void addSymbol(Tree tree, String name) {
      SymbolImpl symbol = (SymbolImpl) symbolsByName.get(name);
      if (symbol != null) {
        symbol.setKind(Symbol.Kind.OTHER);
      } else {
        symbolsByName.put(name, symbol(tree));
      }
    }

    @Override
    public void visitFunctionDef(FunctionDef functionDef) {
      addSymbol(functionDef, functionDef.name().name());
    }

    @Override
    public void visitClassDef(ClassDef classDef) {
      addSymbol(classDef, classDef.name().name());
    }

    @Override
    public void visitAssignmentStatement(AssignmentStatement assignmentStatement) {
      assignmentsLhs((assignmentStatement)).stream()
        .map(SymbolUtils::boundNamesFromExpression)
        .flatMap(Collection::stream)
        .forEach(name -> addSymbol(name, name.name()));
      super.visitAssignmentStatement(assignmentStatement);
    }

    @Override
    public void visitAnnotatedAssignment(AnnotatedAssignment annotatedAssignment) {
      if (annotatedAssignment.variable().is(Kind.NAME)) {
        Name variable = (Name) annotatedAssignment.variable();
        addSymbol(variable, variable.name());
      }
      super.visitAnnotatedAssignment(annotatedAssignment);
    }

    @Override
    public void visitImportFrom(ImportFrom importFrom) {
      DottedName moduleTree = importFrom.module();
      String moduleName = moduleTree != null
        ? moduleTree.names().stream().map(Name::name).collect(Collectors.joining("."))
        : null;
      if (!importFrom.isWildcardImport()) {
        createImportedNames(importFrom.importedNames(), moduleName, importFrom.dottedPrefixForModule());
      }
    }

    @Override
    public void visitImportName(ImportName importName) {
      createImportedNames(importName.modules(), null, Collections.emptyList());
    }

    private void createImportedNames(List<AliasedName> importedNames, @Nullable String fromModuleName, List<Token> dottedPrefix) {
      importedNames.forEach(importedName -> {
        Name nameTree = importedName.dottedName().names().get(0);
        String fullyQualifiedName = fromModuleName != null
          ? (fromModuleName + "." + nameTree.name())
          : nameTree.name();
        if (!dottedPrefix.isEmpty()) {
          return;
        }
        Name alias = importedName.alias();
        String symbolName = alias == null ? nameTree.name() : alias.name();
        importedSymbols.put(symbolName, new SymbolImpl(symbolName, fullyQualifiedName));
      });
    }
  }

  private static class GlobalSymbolsReadVisitor extends BaseTreeVisitor {
    private Map<String, Symbol> symbolsByName;
    private Map<String, Symbol> importedSymbols;

    GlobalSymbolsReadVisitor(Map<String, Symbol> symbolsByName, Map<String, Symbol> importedSymbols) {
      this.symbolsByName = symbolsByName;
      this.importedSymbols = importedSymbols;
    }

    @Override
    public void visitClassDef(ClassDef classDef) {
      resolveTypeHierarchy(classDef, symbolsByName.get(classDef.name().name()), symbolsByName, importedSymbols);
    }
  }

  private static void resolveTypeHierarchy(ClassDef classDef, @Nullable Symbol symbol, Map<String, Symbol> symbolsByName, Map<String, Symbol> importedSymbols) {
    if (symbol == null || !Symbol.Kind.CLASS.equals(symbol.kind())) {
      return;
    }
    ClassSymbolImpl classSymbol = (ClassSymbolImpl) symbol;
    ArgList argList = classDef.args();
    classSymbol.setHasUnresolvedTypeHierarchy(false);
    if (argList == null) {
      return;
    }
    for (Argument argument : argList.arguments()) {
      if (!argument.is(Kind.REGULAR_ARGUMENT) || !(((RegularArgument) argument).expression() instanceof HasSymbol)) {
        classSymbol.setHasUnresolvedTypeHierarchy(true);
        return;
      }
      Expression expression = ((RegularArgument) argument).expression();
      Symbol argumentSymbol = ((HasSymbol) expression).symbol();
      if (argumentSymbol == null && expression.is(Kind.NAME)) {
        String name = ((Name) expression).name();
        argumentSymbol = symbolsByName.get(name);
        if (argumentSymbol == null) {
          argumentSymbol = importedSymbols.get(name);
        }
      }
      if (argumentSymbol == null) {
        classSymbol.setHasUnresolvedTypeHierarchy(true);
        return;
      }
      if (BuiltinSymbols.all().contains(argumentSymbol.fullyQualifiedName())) {
        classSymbol.addSuperClass(argumentSymbol);
        continue;
      }
      if (!Symbol.Kind.CLASS.equals(argumentSymbol.kind())) {
        classSymbol.setHasUnresolvedTypeHierarchy(true);
      }
      classSymbol.addSuperClass(argumentSymbol);
    }
  }

  static void resolveTypeHierarchy(ClassDef classDef, @Nullable Symbol symbol) {
    resolveTypeHierarchy(classDef, symbol, Collections.emptyMap(), Collections.emptyMap());
  }
}
