package com.blackduck.integration.detectable.detectables.xcode;

import java.io.File;
import java.io.IOException;

import org.jetbrains.annotations.Nullable;

import com.blackduck.integration.bdio.graph.BasicDependencyGraph;
import com.blackduck.integration.common.util.finder.FileFinder;
import com.blackduck.integration.detectable.Detectable;
import com.blackduck.integration.detectable.DetectableEnvironment;
import com.blackduck.integration.detectable.detectable.DetectableAccuracyType;
import com.blackduck.integration.detectable.detectable.Requirements;
import com.blackduck.integration.detectable.detectable.annotation.DetectableInfo;
import com.blackduck.integration.detectable.detectable.codelocation.CodeLocation;
import com.blackduck.integration.detectable.detectable.result.DetectableResult;
import com.blackduck.integration.detectable.detectables.swift.lock.PackageResolvedExtractor;
import com.blackduck.integration.detectable.detectables.swift.lock.SwiftPackageResolvedDetectable;
import com.blackduck.integration.detectable.detectables.swift.lock.model.PackageResolvedResult;
import com.blackduck.integration.detectable.extraction.Extraction;
import com.blackduck.integration.detectable.extraction.ExtractionEnvironment;

@DetectableInfo(name = "Xcode Project Lock", language = "Swift", forge = "GITHUB", accuracy = DetectableAccuracyType.HIGH, requirementsMarkdown = "Directory: *.xcodeproj, Files: Package.resolved")
public class XcodeProjectDetectable extends Detectable {
    public static final String PACKAGE_RESOLVED_RELATIVE_PATH = "project.xcworkspace/xcshareddata/swiftpm";

    private final FileFinder fileFinder;
    private final PackageResolvedExtractor packageResolvedExtractor;

    private File foundCodeLocationFile;
    @Nullable
    private File foundPackageResolvedFile;

    public XcodeProjectDetectable(DetectableEnvironment environment, FileFinder fileFinder, PackageResolvedExtractor packageResolvedExtractor) {
        super(environment);
        this.fileFinder = fileFinder;
        this.packageResolvedExtractor = packageResolvedExtractor;
    }

    @Override
    public DetectableResult applicable() {
        Requirements requirements = new Requirements(fileFinder, environment);
        foundCodeLocationFile = requirements.directory("*.xcodeproj");
        return requirements.result();
    }

    @Override
    public DetectableResult extractable() {
        Requirements requirements = new Requirements(fileFinder, environment);
        File swiftPMDirectory = new File(foundCodeLocationFile, PACKAGE_RESOLVED_RELATIVE_PATH);
        foundPackageResolvedFile = requirements.optionalFile(swiftPMDirectory, SwiftPackageResolvedDetectable.PACKAGE_RESOLVED_FILENAME, () -> {/* no-op */});
        return requirements.result();
    }

    @Override
    public Extraction extract(ExtractionEnvironment extractionEnvironment) throws IOException {
        if (foundPackageResolvedFile == null) {
            return Extraction.success(new CodeLocation(new BasicDependencyGraph(), foundCodeLocationFile));
        }

        PackageResolvedResult result = packageResolvedExtractor.extract(foundPackageResolvedFile);
        return result.getFailedDetectableResult()
            .map(Extraction::failure)
            .orElse(Extraction.success(new CodeLocation(result.getDependencyGraph(), foundCodeLocationFile)));
    }

}
