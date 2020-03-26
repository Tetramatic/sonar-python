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

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.internal.DefaultSensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.internal.ConfigurationBridge;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonar.plugins.python.Python;
import org.sonar.plugins.python.TestUtils;
import org.sonar.plugins.python.warnings.AnalysisWarningsWrapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

public class Flake8ImportSensorTest {

  public static final String FILE1_PATH = "src/file1.py";
  public static final String RULE_E203 = "E203";
  private final File baseDir = new File("src/test/resources/org/sonar/plugins/python/flake8");
  private final SensorContextTester context = SensorContextTester.create(baseDir);
  private final AnalysisWarningsWrapper analysisWarnings = spy(AnalysisWarningsWrapper.class);

  @Rule
  public LogTester logTester = new LogTester();

  @Test
  public void parse_report() {
    context.settings().setProperty(Flake8ImportSensor.REPORT_PATH_KEY, "flake8-report.txt");

    File file = new File(baseDir, FILE1_PATH);
    DefaultInputFile inputFile = TestInputFileBuilder.create("", FILE1_PATH)
      .setLanguage(Python.KEY)
      .initMetadata(TestUtils.fileContent(file, StandardCharsets.UTF_8))
      .build();
    context.fileSystem().add(inputFile);

    context.setActiveRules(
      new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder()
        .setRuleKey(RuleKey.of(Flake8RuleRepository.REPOSITORY_KEY, RULE_E203))
        .setName("Invalid name")
        .build())
      .build());

    Flake8ImportSensor sensor = new Flake8ImportSensor(context.config(), analysisWarnings);
    sensor.execute(context);
    assertThat(context.allIssues()).hasSize(1);
    assertThat(context.allIssues()).extracting(issue -> issue.primaryLocation().inputComponent().key())
      .containsOnly(inputFile.key());
  }

  @Test
  public void sensor_descriptor() {
    DefaultSensorDescriptor descriptor = new DefaultSensorDescriptor();
    new Flake8ImportSensor(context.config(), analysisWarnings).describe(descriptor);
    assertThat(descriptor.name()).isEqualTo("Flake8ImportSensor");
    assertThat(descriptor.languages()).containsOnly("py");
    assertThat(descriptor.type()).isEqualTo(InputFile.Type.MAIN);
    assertThat(descriptor.ruleRepositories()).containsExactly(Flake8RuleRepository.REPOSITORY_KEY);
    Predicate<Configuration> configurationPredicate = descriptor.configurationPredicate();
    assertThat(configurationPredicate.test(configuration(ImmutableMap.of(Flake8ImportSensor.REPORT_PATH_KEY, "something")))).isTrue();
    assertThat(configurationPredicate.test(configuration(ImmutableMap.of("xxx", "yyy")))).isFalse();
  }

  @Test
  public void no_default_report_log() {
    SensorContextTester defaultContext = SensorContextTester.create(baseDir);
    Flake8ImportSensor sensor = new Flake8ImportSensor(defaultContext.config(), analysisWarnings);
    sensor.execute(defaultContext);
    assertThat(logTester.logs(LoggerLevel.DEBUG)).contains("No report was found for sonar.python.flake8.reportPath using default pattern flake8-reports/flake8-result-*.txt");
  }

  private static Configuration configuration(Map<String, String> mapproperties) {
    return new ConfigurationBridge(new MapSettings().addProperties(mapproperties));
  }

}
