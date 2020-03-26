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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class Flake8ReportParser {
  private static final Pattern PATTERN = Pattern.compile("([^:]+):([0-9]+):([0-9]+): (\\S+) (.*)");
  private static final Logger LOG = Loggers.get(Flake8ReportParser.class);

  public Issue parseLine(String line) {
    // Parse the output of Flake8. Example of the format:
    //
    // app/start.py:42:45: Q000 Remove bad quotes
    // ...

    Issue issue = null;

    int linenr;
    String filename = null;
    String ruleid = null;
    String descr = null;

    if (line.length() > 0) {
        Matcher m = PATTERN.matcher(line);
        if (m.matches() && m.groupCount() == 5) {
            filename = m.group(1);
            linenr = Integer.valueOf(m.group(2));
            ruleid = m.group(4);
            descr = m.group(5);
            issue = new Issue(filename, linenr, ruleid, descr);
        } else {
            LOG.debug("Cannot parse the line: {}", line);
        }
    } else {
        LOG.trace("Classifying as detail and ignoring line '{}'", line);
    }
    return issue;
  }

}
