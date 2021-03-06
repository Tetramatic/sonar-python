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

import java.io.File;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.plugins.python.api.PythonFile;
import org.sonar.plugins.python.api.symbols.FunctionSymbol;
import org.sonar.plugins.python.api.symbols.Symbol;
import org.sonar.plugins.python.api.tree.ArgList;
import org.sonar.plugins.python.api.tree.Argument;
import org.sonar.plugins.python.api.tree.AssignmentStatement;
import org.sonar.plugins.python.api.tree.ClassDef;
import org.sonar.plugins.python.api.tree.Expression;
import org.sonar.plugins.python.api.tree.FileInput;
import org.sonar.plugins.python.api.tree.HasSymbol;
import org.sonar.plugins.python.api.tree.ListLiteral;
import org.sonar.plugins.python.api.tree.Name;
import org.sonar.plugins.python.api.tree.ParenthesizedExpression;
import org.sonar.plugins.python.api.tree.RegularArgument;
import org.sonar.plugins.python.api.tree.Tree;
import org.sonar.plugins.python.api.tree.Tree.Kind;
import org.sonar.plugins.python.api.tree.Tuple;
import org.sonar.plugins.python.api.tree.UnpackingExpression;
import org.sonar.python.tree.TreeUtils;
import org.sonar.python.types.InferredTypes;
import org.sonar.python.types.TypeShedPythonFile;

public class SymbolUtils {

  private static final String SEND_MESSAGE = "send_message";
  private static final String SET_COOKIE = "set_cookie";
  private static final String SET_SIGNED_COOKIE = "set_signed_cookie";
  private static final String EQ = "__eq__";

  private SymbolUtils() {
  }

  public static String fullyQualifiedModuleName(String packageName, String fileName) {
    int extensionIndex = fileName.lastIndexOf('.');
    String moduleName = extensionIndex > 0
      ? fileName.substring(0, extensionIndex)
      : fileName;
    if (moduleName.equals("__init__")) {
      return packageName;
    }
    return packageName.isEmpty()
      ? moduleName
      : (packageName + "." + moduleName);
  }

  public static Set<Symbol> globalSymbols(FileInput fileInput, String packageName, PythonFile pythonFile) {
    SymbolTableBuilder symbolTableBuilder = new SymbolTableBuilder(packageName, pythonFile);
    String fullyQualifiedModuleName = SymbolUtils.fullyQualifiedModuleName(packageName, pythonFile.fileName());
    fileInput.accept(symbolTableBuilder);
    Set<Symbol> globalSymbols = new HashSet<>();
    for (Symbol globalVariable : fileInput.globalVariables()) {
      if (globalVariable.kind() == Symbol.Kind.CLASS) {
        globalSymbols.add(((ClassSymbolImpl) globalVariable).copyWithoutUsages());
      } else if (globalVariable.kind() == Symbol.Kind.FUNCTION) {
        globalSymbols.add(new FunctionSymbolImpl(globalVariable.name(), ((FunctionSymbol) globalVariable)));
      } else {
        globalSymbols.add(new SymbolImpl(globalVariable.name(), fullyQualifiedModuleName + "." + globalVariable.name()));
      }
    }
    return globalSymbols;
  }

  static void resolveTypeHierarchy(ClassDef classDef, @Nullable Symbol symbol) {
    if (symbol == null || !Symbol.Kind.CLASS.equals(symbol.kind())) {
      return;
    }
    ClassSymbolImpl classSymbol = (ClassSymbolImpl) symbol;
    ArgList argList = classDef.args();
    if (argList == null) {
      return;
    }
    for (Argument argument : argList.arguments()) {
      if (argument.is(Kind.REGULAR_ARGUMENT) && (((RegularArgument) argument).expression() instanceof HasSymbol)) {
        Expression expression = ((RegularArgument) argument).expression();
        Symbol argumentSymbol = ((HasSymbol) expression).symbol();
        if (argumentSymbol != null) {
          classSymbol.addSuperClass(argumentSymbol);
          continue;
        }
      }
      classSymbol.setHasSuperClassWithoutSymbol();
    }
  }

  public static List<Expression> assignmentsLhs(AssignmentStatement assignmentStatement) {
    return assignmentStatement.lhsExpressions().stream()
      .flatMap(exprList -> exprList.expressions().stream())
      .flatMap(TreeUtils::flattenTuples)
      .collect(Collectors.toList());
  }

  static List<Name> boundNamesFromExpression(@CheckForNull Tree tree) {
    List<Name> names = new ArrayList<>();
    if (tree == null) {
      return names;
    }
    if (tree.is(Tree.Kind.NAME)) {
      names.add(((Name) tree));
    } else if (tree.is(Tree.Kind.TUPLE)) {
      ((Tuple) tree).elements().forEach(t -> names.addAll(boundNamesFromExpression(t)));
    } else if (tree.is(Kind.LIST_LITERAL)) {
      ((ListLiteral) tree).elements().expressions().forEach(t -> names.addAll(boundNamesFromExpression(t)));
    } else if (tree.is(Kind.PARENTHESIZED)) {
      names.addAll(boundNamesFromExpression(((ParenthesizedExpression) tree).expression()));
    } else if (tree.is(Kind.UNPACKING_EXPR)) {
      names.addAll(boundNamesFromExpression(((UnpackingExpression) tree).expression()));
    }
    return names;
  }

  public static String pythonPackageName(File file, File projectBaseDir) {
    File currentDirectory = file.getParentFile();
    Deque<String> packages = new ArrayDeque<>();
    while (!currentDirectory.getAbsolutePath().equals(projectBaseDir.getAbsolutePath())) {
      File initFile = new File(currentDirectory, "__init__.py");
      if (!initFile.exists()) {
        break;
      }
      packages.push(currentDirectory.getName());
      currentDirectory = currentDirectory.getParentFile();
    }
    return String.join(".", packages);
  }

  @CheckForNull
  static Path pathOf(PythonFile pythonFile) {
    try {
      URI uri = pythonFile.uri();
      if ("file".equalsIgnoreCase(uri.getScheme())) {
        return Paths.get(uri);
      }
      return null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  public static Map<String, Set<Symbol>> externalModulesSymbols() {
    Map<String, Set<Symbol>> globalSymbols = new HashMap<>();

    globalSymbols.put("flask_mail", new HashSet<>(Arrays.asList(
      classSymbol("Mail", "flask_mail.Mail", "send", SEND_MESSAGE),
      classSymbol("Connection", "flask_mail.Connection", "send", SEND_MESSAGE)
      )));
    globalSymbols.put("smtplib", new HashSet<>(Arrays.asList(
      classSymbol("SMTP", "smtplib.SMTP", "sendmail", SEND_MESSAGE, "starttls"),
      classSymbol("SMTP_SSL", "smtplib.SMTP_SSL", "sendmail", SEND_MESSAGE)
    )));
    globalSymbols.put("zipfile", Collections.singleton(classSymbol("ZipFile", "zipfile.ZipFile", "extractall")));
    globalSymbols.put("http.cookies", new HashSet<>(Collections.singletonList(classSymbol("SimpleCookie", "http.cookies.SimpleCookie"))));

    globalSymbols.put("django.http", new HashSet<>(Arrays.asList(
      classSymbol("HttpResponse", "django.http.HttpResponse", SET_COOKIE, SET_SIGNED_COOKIE, "__setitem__"),
      classSymbol("HttpResponseRedirect", "django.http.HttpResponseRedirect", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponsePermanentRedirect", "django.http.HttpResponsePermanentRedirect", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseNotModified", "django.http.HttpResponseNotModified", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseNotFound", "django.http.HttpResponseNotFound", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseForbidden", "django.http.HttpResponseForbidden", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseNotAllowed", "django.http.HttpResponseNotAllowed", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseGone", "django.http.HttpResponseGone", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseServerError", "django.http.HttpResponseServerError", SET_COOKIE, SET_SIGNED_COOKIE),
      classSymbol("HttpResponseBadRequest", "django.http.HttpResponseBadRequest", SET_COOKIE, SET_SIGNED_COOKIE)
    )));

    globalSymbols.put("django.http.response", new HashSet<>(Collections.singleton(
      classSymbol("HttpResponse", "django.http.response.HttpResponse")
    )));

    ClassSymbolImpl flaskResponse = classSymbol("Response", "flask.Response", SET_COOKIE);

    FunctionSymbolImpl makeResponse = new FunctionSymbolImpl("make_response", "flask.make_response", false, false, false, Collections.emptyList());
    makeResponse.setDeclaredReturnType(InferredTypes.runtimeType(flaskResponse));

    FunctionSymbolImpl redirect = new FunctionSymbolImpl("redirect", "flask.redirect", false, false, false, Collections.emptyList());
    redirect.setDeclaredReturnType(InferredTypes.runtimeType(flaskResponse));

    globalSymbols.put("flask", new HashSet<>(Arrays.asList(
      flaskResponse,
      makeResponse,
      redirect
    )));

    globalSymbols.put("werkzeug.datastructures", new HashSet<>(Collections.singleton(
      classSymbol("Headers", "werkzeug.datastructures.Headers", "set", "setdefault", "__setitem__")
    )));

    // TODO To be removed once we import 'collections' from typeshed
    globalSymbols.put("collections", new HashSet<>(Arrays.asList(
      classSymbol("deque", "collections.deque", EQ),
      classSymbol("UserList", "collections.UserList", EQ),
      classSymbol("UserDict", "collections.UserDict", EQ),
      classSymbol("ChainMap", "collections.ChainMap", EQ),
      classSymbol("Counter", "collections.Counter", EQ),
      classSymbol("OrderedDict", "collections.OrderedDict", EQ),
      classSymbol("defaultdict", "collections.defaultdict", EQ)
    )));

    return globalSymbols;
  }

  private static ClassSymbolImpl classSymbol(String name, String fullyQualifiedName, String... members) {
    ClassSymbolImpl classSymbol = new ClassSymbolImpl(name, fullyQualifiedName);
    classSymbol.addMembers(Arrays.stream(members).map(m -> new SymbolImpl(m, fullyQualifiedName + "." + m)).collect(Collectors.toSet()));
    return classSymbol;
  }

  public static boolean isTypeShedFile(PythonFile pythonFile) {
    return pythonFile instanceof TypeShedPythonFile;
  }

}
