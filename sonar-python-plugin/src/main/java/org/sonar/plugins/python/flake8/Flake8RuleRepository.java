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
package org.sonar.plugins.python.flake8;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.api.server.rule.RulesDefinitionXmlLoader;
import org.sonar.plugins.python.Python;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Flake8RuleRepository implements RulesDefinition {

  public static final String REPOSITORY_NAME = "Flake8";
  public static final String REPOSITORY_KEY = REPOSITORY_NAME;

  public static final String RULES_FILE = "/org/sonar/plugins/python/flake8/rules.xml";

  private final RulesDefinitionXmlLoader xmlLoader;

  public Flake8RuleRepository(RulesDefinitionXmlLoader xmlLoader) {
    this.xmlLoader = xmlLoader;
  }

  @Override
  public void define(Context context) {
    NewRepository repository = context
      .createRepository(REPOSITORY_KEY, Python.KEY)
      .setName(REPOSITORY_NAME);
    xmlLoader.load(repository, getClass().getResourceAsStream(RULES_FILE), UTF_8.name());
    repository.done();
  }

}
