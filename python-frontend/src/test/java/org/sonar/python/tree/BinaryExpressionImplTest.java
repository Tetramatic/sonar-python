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
package org.sonar.python.tree;

import org.junit.Test;
import org.sonar.python.types.InferredTypes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.python.PythonTestUtils.lastExpression;
import static org.sonar.python.types.InferredTypes.BOOL;
import static org.sonar.python.types.InferredTypes.INT;
import static org.sonar.python.types.InferredTypes.STR;
import static org.sonar.python.types.InferredTypes.anyType;
import static org.sonar.python.types.InferredTypes.or;

public class BinaryExpressionImplTest {

  @Test
  public void type() {
    assertThat(lastExpression("42 + 43").type()).isEqualTo(INT);
    assertThat(lastExpression("'foo' + 'bar'").type()).isEqualTo(STR);
    assertThat(lastExpression("True + False").type()).isEqualTo(InferredTypes.anyType());
    assertThat(lastExpression("42 + ''").type()).isEqualTo(InferredTypes.anyType());
    assertThat(lastExpression("'' + 42").type()).isEqualTo(InferredTypes.anyType());
    assertThat(lastExpression("'' // 42").type()).isEqualTo(InferredTypes.anyType());
  }

  @Test
  public void logical_expressions() {
    assertThat(lastExpression("42 or 43").type()).isEqualTo(INT);
    assertThat(lastExpression("42 and 43").type()).isEqualTo(INT);
    assertThat(lastExpression("42 or ''").type()).isEqualTo(or(INT, STR));
    assertThat(lastExpression("42 or xxx").type()).isEqualTo(anyType());
    assertThat(lastExpression("42 or True or ''").type()).isEqualTo(or(or(INT, STR), BOOL));
  }

  @Test
  public void type_dependencies() {
    BinaryExpressionImpl binary = ((BinaryExpressionImpl) lastExpression("42 or ''"));
    assertThat(binary.typeDependencies()).containsExactly(binary.leftOperand(), binary.rightOperand());

    binary = ((BinaryExpressionImpl) lastExpression("42 // ''"));
    assertThat(binary.typeDependencies()).isEmpty();
  }
}
