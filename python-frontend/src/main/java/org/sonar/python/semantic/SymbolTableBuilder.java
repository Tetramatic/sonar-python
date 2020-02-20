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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.plugins.python.api.PythonFile;
import org.sonar.plugins.python.api.symbols.ClassSymbol;
import org.sonar.plugins.python.api.symbols.Symbol;
import org.sonar.plugins.python.api.symbols.Usage;
import org.sonar.plugins.python.api.tree.AliasedName;
import org.sonar.plugins.python.api.tree.AnnotatedAssignment;
import org.sonar.plugins.python.api.tree.AnyParameter;
import org.sonar.plugins.python.api.tree.AssignmentStatement;
import org.sonar.plugins.python.api.tree.BaseTreeVisitor;
import org.sonar.plugins.python.api.tree.CallExpression;
import org.sonar.plugins.python.api.tree.ClassDef;
import org.sonar.plugins.python.api.tree.CompoundAssignmentStatement;
import org.sonar.plugins.python.api.tree.ComprehensionExpression;
import org.sonar.plugins.python.api.tree.ComprehensionFor;
import org.sonar.plugins.python.api.tree.Decorator;
import org.sonar.plugins.python.api.tree.DottedName;
import org.sonar.plugins.python.api.tree.ExceptClause;
import org.sonar.plugins.python.api.tree.Expression;
import org.sonar.plugins.python.api.tree.FileInput;
import org.sonar.plugins.python.api.tree.ForStatement;
import org.sonar.plugins.python.api.tree.FunctionDef;
import org.sonar.plugins.python.api.tree.FunctionLike;
import org.sonar.plugins.python.api.tree.GlobalStatement;
import org.sonar.plugins.python.api.tree.HasSymbol;
import org.sonar.plugins.python.api.tree.ImportFrom;
import org.sonar.plugins.python.api.tree.ImportName;
import org.sonar.plugins.python.api.tree.LambdaExpression;
import org.sonar.plugins.python.api.tree.Name;
import org.sonar.plugins.python.api.tree.NonlocalStatement;
import org.sonar.plugins.python.api.tree.Parameter;
import org.sonar.plugins.python.api.tree.ParameterList;
import org.sonar.plugins.python.api.tree.QualifiedExpression;
import org.sonar.plugins.python.api.tree.Token;
import org.sonar.plugins.python.api.tree.Tree;
import org.sonar.plugins.python.api.tree.Tree.Kind;
import org.sonar.plugins.python.api.tree.TupleParameter;
import org.sonar.plugins.python.api.tree.WithItem;
import org.sonar.python.tree.ClassDefImpl;
import org.sonar.python.tree.ComprehensionExpressionImpl;
import org.sonar.python.tree.DictCompExpressionImpl;
import org.sonar.python.tree.FileInputImpl;
import org.sonar.python.tree.FunctionDefImpl;
import org.sonar.python.tree.ImportFromImpl;
import org.sonar.python.tree.LambdaExpressionImpl;
import org.sonar.python.types.TypeInference;
import org.sonar.python.types.TypeShedVisitor;

import static org.sonar.python.semantic.ProjectLevelSymbolVisitor.resolveTypeHierarchy;
import static org.sonar.python.semantic.SymbolUtils.boundNamesFromExpression;

// SymbolTable based on https://docs.python.org/3/reference/executionmodel.html#naming-and-binding
public class SymbolTableBuilder extends BaseTreeVisitor {
  private String fullyQualifiedModuleName;
  private List<String> filePath;
  private Map<String, Set<Symbol>> globalSymbolsByModuleName;
  private Map<String, Symbol> globalSymbolsByFQN;
  private Map<Tree, Scope> scopesByRootTree;
  private Set<Tree> assignmentLeftHandSides = new HashSet<>();
  private final PythonFile pythonFile;

  public SymbolTableBuilder(PythonFile pythonFile) {
    fullyQualifiedModuleName = null;
    filePath = null;
    globalSymbolsByModuleName = Collections.emptyMap();
    globalSymbolsByFQN = Collections.emptyMap();
    this.pythonFile = pythonFile;
  }

  public SymbolTableBuilder(String packageName, PythonFile pythonFile) {
    this(packageName, pythonFile, Collections.emptyMap());
  }

  public SymbolTableBuilder(String packageName, PythonFile pythonFile, Map<String, Set<Symbol>> globalSymbolsByModuleName) {
    this.pythonFile = pythonFile;
    String fileName = pythonFile.fileName();
    int extensionIndex = fileName.lastIndexOf('.');
    String moduleName = extensionIndex > 0
      ? fileName.substring(0, extensionIndex)
      : fileName;
    filePath = new ArrayList<>(Arrays.asList(packageName.split("\\.")));
    filePath.add(moduleName);
    fullyQualifiedModuleName = SymbolUtils.fullyQualifiedModuleName(packageName, fileName);
    this.globalSymbolsByModuleName = globalSymbolsByModuleName;
    this.globalSymbolsByFQN = globalSymbolsByModuleName.values()
      .stream()
      .flatMap(Collection::stream)
      .filter(symbol -> symbol.fullyQualifiedName() != null)
      .collect(Collectors.toMap(Symbol::fullyQualifiedName, Function.identity()));
  }

  @Override
  public void visitFileInput(FileInput fileInput) {
    scopesByRootTree = new HashMap<>();
    fileInput.accept(new FirstPhaseVisitor());
    fileInput.accept(new SecondPhaseVisitor());
    for (Scope scope : scopesByRootTree.values()) {
      scope.symbols().forEach(symbol -> ((SymbolImpl) symbol).updateChildrenFQNBasedOnType());
      if (scope.rootTree instanceof FunctionLike) {
        FunctionLike funcDef = (FunctionLike) scope.rootTree;
        for (Symbol symbol : scope.symbols()) {
          if (funcDef.is(Kind.LAMBDA)) {
            ((LambdaExpressionImpl) funcDef).addLocalVariableSymbol(symbol);
          } else {
            ((FunctionDefImpl) funcDef).addLocalVariableSymbol(symbol);
          }
        }
        TypeInference.inferTypes(funcDef);
      } else if (scope.rootTree.is(Kind.CLASSDEF)) {
        ClassDefImpl classDef = (ClassDefImpl) scope.rootTree;
        scope.symbols().forEach(classDef::addClassField);
        scope.instanceAttributesByName.values().forEach(classDef::addInstanceField);
      } else if (scope.rootTree.is(Kind.FILE_INPUT)) {
        scope.symbols().stream().filter(s -> !scope.builtinSymbols.contains(s)).forEach(((FileInputImpl) fileInput)::addGlobalVariables);
      } else if (scope.rootTree.is(Kind.DICT_COMPREHENSION)) {
        scope.symbols().forEach(((DictCompExpressionImpl) scope.rootTree)::addLocalVariableSymbol);
      } else if (scope.rootTree instanceof ComprehensionExpression) {
        scope.symbols().forEach(((ComprehensionExpressionImpl) scope.rootTree)::addLocalVariableSymbol);
      }
    }
  }

  private class ScopeVisitor extends BaseTreeVisitor {

    private Deque<Tree> scopeRootTrees = new LinkedList<>();
    protected Scope moduleScope;

    Tree currentScopeRootTree() {
      return scopeRootTrees.peek();
    }

    void enterScope(Tree tree) {
      scopeRootTrees.push(tree);
    }

    Tree leaveScope() {
      return scopeRootTrees.pop();
    }

    Scope currentScope() {
      return scopesByRootTree.get(currentScopeRootTree());
    }
  }

  private class FirstPhaseVisitor extends ScopeVisitor {

    @Override
    public void visitFileInput(FileInput tree) {
      createScope(tree, null);
      enterScope(tree);
      moduleScope = currentScope();
      Map<String, Symbol> typeShedSymbols = TypeShedVisitor.typeShedSymbols();
      for (String name : BuiltinSymbols.all()) {
        currentScope().createBuiltinSymbol(name, typeShedSymbols);
      }
      super.visitFileInput(tree);
    }

    @Override
    public void visitLambda(LambdaExpression pyLambdaExpressionTree) {
      createScope(pyLambdaExpressionTree, currentScope());
      enterScope(pyLambdaExpressionTree);
      createParameters(pyLambdaExpressionTree);
      super.visitLambda(pyLambdaExpressionTree);
      leaveScope();
    }

    @Override
    public void visitDictCompExpression(DictCompExpressionImpl tree) {
      createScope(tree, currentScope());
      enterScope(tree);
      super.visitDictCompExpression(tree);
      leaveScope();
    }

    @Override
    public void visitPyListOrSetCompExpression(ComprehensionExpression tree) {
      createScope(tree, currentScope());
      enterScope(tree);
      super.visitPyListOrSetCompExpression(tree);
      leaveScope();
    }

    @Override
    public void visitFunctionDef(FunctionDef pyFunctionDefTree) {
      String functionName = pyFunctionDefTree.name().name();
      String fullyQualifiedName = getFullyQualifiedName(functionName);
      currentScope().addFunctionSymbol(pyFunctionDefTree, fullyQualifiedName);
      createScope(pyFunctionDefTree, currentScope());
      enterScope(pyFunctionDefTree);
      createParameters(pyFunctionDefTree);
      super.visitFunctionDef(pyFunctionDefTree);
      leaveScope();
    }

    @Override
    public void visitClassDef(ClassDef pyClassDefTree) {
      String className = pyClassDefTree.name().name();
      String fullyQualifiedName = getFullyQualifiedName(className);
      currentScope().addClassSymbol(pyClassDefTree, fullyQualifiedName);
      createScope(pyClassDefTree, currentScope());
      enterScope(pyClassDefTree);
      super.visitClassDef(pyClassDefTree);
      leaveScope();
    }

    @CheckForNull
    private String getFullyQualifiedName(String name) {
      String prefix = scopeQualifiedName();
      if (prefix != null) {
        return prefix.isEmpty() ? name : (prefix + "." + name);
      }
      return null;
    }

    private String scopeQualifiedName() {
      Tree scopeTree = currentScopeRootTree();
      if (scopeTree.is(Kind.CLASSDEF, Kind.FUNCDEF)) {
        Name name = scopeTree.is(Kind.CLASSDEF)
          ? ((ClassDef) scopeTree).name()
          : ((FunctionDef) scopeTree).name();
        return Optional.ofNullable(name.symbol()).map(Symbol::fullyQualifiedName).orElse(name.name());
      }
      return fullyQualifiedModuleName;
    }

    @Override
    public void visitImportName(ImportName pyImportNameTree) {
      createImportedNames(pyImportNameTree.modules(), null, Collections.emptyList());
      super.visitImportName(pyImportNameTree);
    }

    @Override
    public void visitImportFrom(ImportFrom importFrom) {
      DottedName moduleTree = importFrom.module();
      String moduleName = moduleTree != null
        ? moduleTree.names().stream().map(Name::name).collect(Collectors.joining("."))
        : null;
      if (importFrom.isWildcardImport()) {
        Set<Symbol> importedModuleSymbols = globalSymbolsByModuleName.get(moduleName);
        if (importedModuleSymbols != null) {
          currentScope().createSymbolsFromWildcardImport(importedModuleSymbols);
          ((ImportFromImpl) importFrom).setHasUnresolvedWildcardImport(false);
        } else {
          ((ImportFromImpl) importFrom).setHasUnresolvedWildcardImport(true);
        }
      } else {
        createImportedNames(importFrom.importedNames(), moduleName, importFrom.dottedPrefixForModule());
      }
      super.visitImportFrom(importFrom);
    }

    private void createImportedNames(List<AliasedName> importedNames, @Nullable String fromModuleName, List<Token> dottedPrefix) {
      importedNames.forEach(module -> {
        Name nameTree = module.dottedName().names().get(0);
        String fullyQualifiedName = fromModuleName != null
          ? (fromModuleName + "." + nameTree.name())
          : nameTree.name();
        if (!dottedPrefix.isEmpty()) {
          fullyQualifiedName = resolveFullyQualifiedNameBasedOnRelativeImport(dottedPrefix, fullyQualifiedName);
        }
        Name alias = module.alias();
        if (fromModuleName != null) {
          currentScope().addImportedSymbol(alias == null ? nameTree : alias, fullyQualifiedName, globalSymbolsByFQN);
        } else {
          currentScope().addModuleSymbol(alias == null ? nameTree : alias, fullyQualifiedName, globalSymbolsByModuleName);
        }
      });
    }

    @CheckForNull
    private String resolveFullyQualifiedNameBasedOnRelativeImport(List<Token> dottedPrefix, String moduleName) {
      if (filePath == null || dottedPrefix.size() > filePath.size()) {
        return null;
      }
      String resolvedPackageName = String.join("", filePath.subList(0, filePath.size() - dottedPrefix.size()));
      return resolvedPackageName.isEmpty() ? moduleName : (resolvedPackageName + "." + moduleName);
    }

    @Override
    public void visitForStatement(ForStatement pyForStatementTree) {
      createLoopVariables(pyForStatementTree);
      super.visitForStatement(pyForStatementTree);
    }

    @Override
    public void visitComprehensionFor(ComprehensionFor tree) {
      addCompDeclarationParam(tree.loopExpression());
      super.visitComprehensionFor(tree);
    }

    private void addCompDeclarationParam(Tree tree) {
      boundNamesFromExpression(tree).forEach(name -> addBindingUsage(name, Usage.Kind.COMP_DECLARATION));
    }

    private void createLoopVariables(ForStatement loopTree) {
      loopTree.expressions().forEach(expr ->
        boundNamesFromExpression(expr).forEach(name -> addBindingUsage(name, Usage.Kind.LOOP_DECLARATION)));
    }

    private void createParameters(FunctionLike function) {
      ParameterList parameterList = function.parameters();
      if (parameterList == null || parameterList.all().isEmpty()) {
        return;
      }

      boolean hasSelf = false;
      if (function.isMethodDefinition()) {
        AnyParameter first = parameterList.all().get(0);
        if (first.is(Kind.PARAMETER)) {
          currentScope().createSelfParameter((Parameter) first);
          hasSelf = true;
        }
      }

      parameterList.nonTuple()
        .stream()
        .skip(hasSelf ? 1 : 0)
        .map(Parameter::name)
        .filter(Objects::nonNull)
        .forEach(param -> addBindingUsage(param, Usage.Kind.PARAMETER));

      parameterList.all().stream()
        .filter(param -> param.is(Kind.TUPLE_PARAMETER))
        .map(TupleParameter.class::cast)
        .forEach(this::addTupleParamElementsToBindingUsage);
    }

    private void addTupleParamElementsToBindingUsage(TupleParameter param) {
      param.parameters().stream()
        .filter(p -> p.is(Kind.PARAMETER))
        .map(p -> ((Parameter) p).name())
        .forEach(name -> addBindingUsage(name, Usage.Kind.PARAMETER));
      param.parameters().stream()
        .filter(p -> p.is(Kind.TUPLE_PARAMETER))
        .map(TupleParameter.class::cast)
        .forEach(this::addTupleParamElementsToBindingUsage);
    }

    @Override
    public void visitAssignmentStatement(AssignmentStatement pyAssignmentStatementTree) {
      List<Expression> lhs = SymbolUtils.assignmentsLhs(pyAssignmentStatementTree);

      assignmentLeftHandSides.addAll(lhs);

      lhs.forEach(expression -> boundNamesFromExpression(expression).forEach(name -> addBindingUsage(name, Usage.Kind.ASSIGNMENT_LHS)));

      super.visitAssignmentStatement(pyAssignmentStatementTree);
    }

    @Override
    public void visitAnnotatedAssignment(AnnotatedAssignment annotatedAssignment) {
      if (annotatedAssignment.variable().is(Kind.NAME)) {
        addBindingUsage((Name) annotatedAssignment.variable(), Usage.Kind.ASSIGNMENT_LHS);
      }
      super.visitAnnotatedAssignment(annotatedAssignment);
    }

    @Override
    public void visitCompoundAssignment(CompoundAssignmentStatement pyCompoundAssignmentStatementTree) {
      if (pyCompoundAssignmentStatementTree.lhsExpression().is(Kind.NAME)) {
        addBindingUsage((Name) pyCompoundAssignmentStatementTree.lhsExpression(), Usage.Kind.COMPOUND_ASSIGNMENT_LHS);
      }
      super.visitCompoundAssignment(pyCompoundAssignmentStatementTree);
    }

    @Override
    public void visitGlobalStatement(GlobalStatement pyGlobalStatementTree) {
      pyGlobalStatementTree.variables()
        .forEach(name -> {
          // Global statements are not binding usages, but we consider them as such for symbol creation
          moduleScope.addBindingUsage(name, Usage.Kind.GLOBAL_DECLARATION, null);
          currentScope().addGlobalName(name.name());
        });

      super.visitGlobalStatement(pyGlobalStatementTree);
    }

    @Override
    public void visitNonlocalStatement(NonlocalStatement pyNonlocalStatementTree) {
      pyNonlocalStatementTree.variables().stream()
        .map(Name::name)
        .forEach(name -> currentScope().addNonLocalName(name));
      super.visitNonlocalStatement(pyNonlocalStatementTree);
    }

    @Override
    public void visitExceptClause(ExceptClause exceptClause) {
      boundNamesFromExpression(exceptClause.exceptionInstance()).forEach(name -> addBindingUsage(name, Usage.Kind.EXCEPTION_INSTANCE));
      super.visitExceptClause(exceptClause);
    }

    @Override
    public void visitWithItem(WithItem withItem) {
      boundNamesFromExpression(withItem.expression()).forEach(name -> addBindingUsage(name, Usage.Kind.WITH_INSTANCE));
      super.visitWithItem(withItem);
    }

    private void createScope(Tree tree, @Nullable Scope parent) {
      scopesByRootTree.put(tree, new Scope(parent, tree, pythonFile));
    }

    private void addBindingUsage(Name nameTree, Usage.Kind usage) {
      currentScope().addBindingUsage(nameTree, usage, null);
    }
  }

  /**
   * Read (i.e. non-binding) usages have to be visited in a second phase.
   * They can't be visited in the same phase as write (i.e. binding) usages,
   * since a read usage may appear in the syntax tree "before" it's declared (written).
   */
  private class SecondPhaseVisitor extends ScopeVisitor {

    @Override
    public void visitFileInput(FileInput tree) {
      enterScope(tree);
      super.visitFileInput(tree);
    }

    @Override
    public void visitFunctionDef(FunctionDef pyFunctionDefTree) {
      scan(pyFunctionDefTree.decorators());
      enterScope(pyFunctionDefTree);
      scan(pyFunctionDefTree.name());
      scan(pyFunctionDefTree.parameters());
      scan(pyFunctionDefTree.returnTypeAnnotation());
      scan(pyFunctionDefTree.body());
      leaveScope();
    }

    @Override
    public void visitParameter(Parameter parameter) {
      // parameter default value should not be in the function scope.
      Tree currentScopeTree = leaveScope();
      scan(parameter.defaultValue());
      enterScope(currentScopeTree);
      scan(parameter.name());
      scan(parameter.typeAnnotation());
    }

    @Override
    public void visitAssignmentStatement(AssignmentStatement assignment) {
      super.visitAssignmentStatement(assignment);
      List<Expression> lhs = SymbolUtils.assignmentsLhs(assignment);
      lhs.forEach(expression -> boundNamesFromExpression(expression).forEach(name -> addTypeToSymbol(name, assignment.assignedValue())));
    }

    @Override
    public void visitLambda(LambdaExpression pyLambdaExpressionTree) {
      enterScope(pyLambdaExpressionTree);
      super.visitLambda(pyLambdaExpressionTree);
      leaveScope();
    }

    @Override
    public void visitPyListOrSetCompExpression(ComprehensionExpression tree) {
      enterScope(tree);
      scan(tree.resultExpression());
      ComprehensionFor comprehensionFor = tree.comprehensionFor();
      scan(comprehensionFor.loopExpression());
      leaveScope();
      scan(comprehensionFor.iterable());
      enterScope(tree);
      scan(comprehensionFor.nestedClause());
      leaveScope();
    }

    @Override
    public void visitDictCompExpression(DictCompExpressionImpl tree) {
      enterScope(tree);
      scan(tree.keyExpression());
      scan(tree.valueExpression());
      ComprehensionFor comprehensionFor = tree.comprehensionFor();
      scan(comprehensionFor.loopExpression());
      leaveScope();
      scan(comprehensionFor.iterable());
      enterScope(tree);
      scan(comprehensionFor.nestedClause());
      leaveScope();
    }

    @Override
    public void visitClassDef(ClassDef pyClassDefTree) {
      scan(pyClassDefTree.args());
      scan(pyClassDefTree.decorators());
      enterScope(pyClassDefTree);
      scan(pyClassDefTree.name());
      scan(pyClassDefTree.body());
      resolveTypeHierarchy(pyClassDefTree, pyClassDefTree.name().symbol());
      leaveScope();
    }

    @Override
    public void visitQualifiedExpression(QualifiedExpression qualifiedExpression) {
      // We need to firstly create symbol for qualifier
      super.visitQualifiedExpression(qualifiedExpression);
      if (qualifiedExpression.qualifier() instanceof HasSymbol) {
        Symbol qualifierSymbol = ((HasSymbol) qualifiedExpression.qualifier()).symbol();
        if (qualifierSymbol != null) {
          Usage.Kind usageKind = assignmentLeftHandSides.contains(qualifiedExpression) ? Usage.Kind.ASSIGNMENT_LHS : Usage.Kind.OTHER;
          ((SymbolImpl) qualifierSymbol).addOrCreateChildUsage(qualifiedExpression.name(), usageKind);
        }
      }
    }

    @Override
    public void visitDecorator(Decorator decorator) {
      Name nameTree = decorator.name().names().get(0);
      addSymbolUsage(nameTree);
      super.visitDecorator(decorator);
    }

    @Override
    public void visitName(Name pyNameTree) {
      if (!pyNameTree.isVariable()) {
        return;
      }
      addSymbolUsage(pyNameTree);
      super.visitName(pyNameTree);
    }

    private void addSymbolUsage(Name nameTree) {
      Scope scope = scopesByRootTree.get(currentScopeRootTree());
      SymbolImpl symbol = scope.resolve(nameTree.name());
      // TODO: use Set to improve performances
      if (symbol != null && symbol.usages().stream().noneMatch(usage -> usage.tree().equals(nameTree))) {
        symbol.addUsage(nameTree, Usage.Kind.OTHER);
      }
    }

    private void addTypeToSymbol(Name nameTree, Expression rhs) {
      Type type = rhs.is(Kind.CALL_EXPR) ? getReturnType((CallExpression) rhs) : null;
      SymbolImpl symbol = currentScope().resolve(nameTree.name());
      if (symbol != null && symbol.usages().stream().filter(Usage::isBindingUsage).count() == 1) {
        symbol.setType(type);
      }
    }

    @CheckForNull
    private Type getReturnType(CallExpression rhs) {
      Symbol calleeSymbol = rhs.calleeSymbol();
      if (calleeSymbol == null) {
        return null;
      }
      if (calleeSymbol.kind() == Symbol.Kind.CLASS) {
        ClassSymbol classSymbol = (ClassSymbol) calleeSymbol;
        // type of inherited methods is not handled - See SONARPY-561
        if (classSymbol.superClasses().isEmpty() && !classSymbol.hasUnresolvedTypeHierarchy()) {
          return new Type(calleeSymbol);
        }
      } else if (calleeSymbol.kind() == Symbol.Kind.FUNCTION) {
        Type returnType = ((FunctionSymbolImpl) calleeSymbol).returnType();
        if (returnType != null) {
          return new Type(returnType.symbol());
        }
      }
      return null;
    }

  }
}
