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
package org.sonar.python.checks;

import javax.annotation.Nullable;
import org.sonar.check.Rule;
import org.sonar.plugins.python.api.PythonSubscriptionCheck;
import org.sonar.plugins.python.api.symbols.Symbol;
import org.sonar.plugins.python.api.tree.CallExpression;
import org.sonar.plugins.python.api.tree.Expression;
import org.sonar.plugins.python.api.tree.Name;
import org.sonar.plugins.python.api.tree.RaiseStatement;
import org.sonar.plugins.python.api.tree.Tree;
import org.sonar.python.semantic.BuiltinSymbols;
import org.sonar.python.types.InferredTypes;

import static org.sonar.plugins.python.api.types.BuiltinTypes.BASE_EXCEPTION;

@Rule(key = "S5632")
public class IncorrectExceptionTypeCheck extends PythonSubscriptionCheck {

  private static final String MESSAGE = "Change this code so that it raises an object deriving from BaseException.";

  @Override
  public void initialize(Context context) {
    context.registerSyntaxNodeConsumer(Tree.Kind.RAISE_STMT, ctx -> {
      RaiseStatement raiseStatement = (RaiseStatement) ctx.syntaxNode();
      if (raiseStatement.expressions().isEmpty()) {
        return;
      }
      Expression raisedExpression = raiseStatement.expressions().get(0);
      if (raisedExpression.is(Tree.Kind.CALL_EXPR)) {
        CallExpression callExpression = ((CallExpression) raisedExpression);
        Symbol calleeSymbol = callExpression.calleeSymbol();
        if (!inheritsFromBaseException(calleeSymbol, raisedExpression)) {
          ctx.addIssue(raiseStatement, MESSAGE);
        }
      }
      if (raisedExpression.is(Tree.Kind.NAME)) {
        Symbol symbol = ((Name) raisedExpression).symbol();
        if (!inheritsFromBaseException(symbol, raisedExpression)) {
          ctx.addIssue(raiseStatement, MESSAGE);
        }
      }
      if (raisedExpression.is(Tree.Kind.STRING_LITERAL)) {
        ctx.addIssue(raiseStatement, MESSAGE);
      }
    });
  }

  private static boolean inheritsFromBaseException(@Nullable Symbol symbol, Expression raisedExpression) {
    if (symbol == null) {
      // S3827 will raise the issue in this case
      return true;
    }
    if (BuiltinSymbols.EXCEPTIONS.contains(symbol.fullyQualifiedName()) || BuiltinSymbols.EXCEPTIONS_PYTHON2.contains(symbol.fullyQualifiedName())) {
      return true;
    }
    if (Symbol.Kind.CLASS.equals(symbol.kind())) {
      return InferredTypes.runtimeType(symbol).canBeOrExtend(BASE_EXCEPTION);
    }
    if (BuiltinSymbols.all().contains(symbol.fullyQualifiedName())) {
      return false;
    }
    return raisedExpression.type().canBeOrExtend(BASE_EXCEPTION);
  }
}
