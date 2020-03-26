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

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRule;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.config.Configuration;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.python.PythonReportSensor;
import org.sonar.plugins.python.warnings.AnalysisWarningsWrapper;

public class Flake8ImportSensor extends PythonReportSensor {
  public static final String REPORT_PATH_KEY = "sonar.python.flake8.reportPath";
  private static final String DEFAULT_REPORT_PATH = "flake8-reports/flake8-result-*.txt";

  private static final Logger LOG = Loggers.get(Flake8ImportSensor.class);
  private static final Flake8RuleParser flake8Rules = new Flake8RuleParser(Flake8RuleRepository.RULES_FILE);
  private static final Set<String> warningAlreadyLogged = new HashSet<>();

  public Flake8ImportSensor(Configuration conf, AnalysisWarningsWrapper analysisWarnings) {
    super(conf, analysisWarnings, "Flake8");
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    super.describe(descriptor);
    descriptor
      .createIssuesForRuleRepository(Flake8RuleRepository.REPOSITORY_KEY)
      .onlyWhenConfiguration(conf -> conf.hasKey(REPORT_PATH_KEY));
  }

  @Override
  protected String reportPathKey() {
    return REPORT_PATH_KEY;
  }

  @Override
  protected String defaultReportPath() {
    return DEFAULT_REPORT_PATH;
  }

  @Override
  protected void processReports(final SensorContext context, List<File> reports) {
    List<Issue> issues = new LinkedList<>();
    for (File report : reports) {
      try {
        issues.addAll(parse(report, context.fileSystem()));
      } catch (java.io.FileNotFoundException e) {
        LOG.error("Report '{}' cannot be found, details: '{}'", report, e);
      } catch (IOException e) {
        LOG.error("Report '{}' cannot be read, details: '{}'", report, e);
      }
    }

    saveIssues(issues, context);
  }

  private static List<Issue> parse(File report, FileSystem fileSystem) throws IOException {
    List<Issue> issues = new LinkedList<>();

    Flake8ReportParser parser = new Flake8ReportParser();
    Scanner sc;
    for (sc = new Scanner(report.toPath(), fileSystem.encoding().name()); sc.hasNext(); ) {
      String line = sc.nextLine();
      Issue issue = parser.parseLine(line);
      if (issue != null) {
        issues.add(issue);
      }
    }
    sc.close();
    return issues;
  }

  private static void saveIssues(List<Issue> issues, SensorContext context) {
    FileSystem fileSystem = context.fileSystem();
    for (Issue flake8Issue : issues) {
      String filepath = flake8Issue.getFilename();
      InputFile pyfile = fileSystem.inputFile(fileSystem.predicates().hasPath(filepath));
      if (pyfile != null) {
        ActiveRule rule = context.activeRules().find(RuleKey.of(Flake8RuleRepository.REPOSITORY_KEY, flake8Issue.getRuleId()));
        processRule(flake8Issue, pyfile, rule, context);
      } else {
        LOG.warn("Cannot find the file '{}' in SonarQube, ignoring violation", filepath);
      }
    }
  }

  public static void processRule(Issue flake8Issue, InputFile pyfile, @Nullable ActiveRule rule, SensorContext context) {
    if (rule != null) {
      NewIssue newIssue = context
        .newIssue()
        .forRule(rule.ruleKey());
      newIssue.at(
        newIssue.newLocation()
          .on(pyfile)
          .at(pyfile.selectLine(flake8Issue.getLine()))
          .message(flake8Issue.getDescription()));
      newIssue.save();
    } else if (!flake8Rules.hasRuleDefinition(flake8Issue.getRuleId())) {
      logUnknownRuleWarning(flake8Issue.getRuleId());
    }
  }

  private static void logUnknownRuleWarning(String ruleId) {
    if (!warningAlreadyLogged.contains(ruleId)) {
      warningAlreadyLogged.add(ruleId);
      LOG.warn("Flake8 rule '{}' is unknown in Sonar", ruleId);
    }
  }

  // Visible for testing
  static void clearLoggedWarnings() {
    warningAlreadyLogged.clear();
  }

}
