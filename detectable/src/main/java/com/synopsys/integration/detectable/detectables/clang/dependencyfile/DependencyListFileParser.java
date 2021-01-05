/**
 * detectable
 *
 * Copyright (c) 2021 Synopsys, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.synopsys.integration.detectable.detectables.clang.dependencyfile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DependencyListFileParser {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public List<String> parseDepsMk(File depsMkFile) {
        try {
            String depsMkText = FileUtils.readFileToString(depsMkFile, StandardCharsets.UTF_8);
            return parseDepsMk(depsMkText);
        } catch (Exception e) {
            logger.warn(String.format("Error getting dependency file paths from '%s': %s", depsMkFile.getAbsolutePath(), e.getMessage()));
            return Collections.emptyList();
        }
    }

    public List<String> parseDepsMk(String depsMkText) {
        String[] depsMkTextParts = depsMkText.split(": ");
        if (depsMkTextParts.length != 2) {
            logger.warn(String.format("Unable to split mk text parts reasonably: %s", String.join(" ", depsMkTextParts)));
            return Collections.emptyList();
        }
        String depsListString = depsMkTextParts[1];
        logger.trace(String.format("dependencies: %s", depsListString));

        depsListString = depsListString.replaceAll("\n", " ");
        logger.trace(String.format("dependencies, newlines removed: %s", depsListString));

        depsListString = depsListString.replaceAll("\\\\", " "); //TODO: This does not work on Windows paths.
        logger.trace(String.format("dependencies, backslashes removed: %s", depsListString));

        String[] deps = depsListString.split("\\s+");
        List<String> depsList = Arrays.stream(deps)
                                    .filter(StringUtils::isNotBlank)
                                    .map(this::normalize)
                                    .collect(Collectors.toList());
        return depsList;
    }

    private String normalize(String rawPath) {
        String normalizedPath = Paths.get(rawPath).normalize().toString();
        logger.trace(String.format("Normalized %s to %s", rawPath, normalizedPath));
        return normalizedPath;
    }
}
